package org.jellyfin.mobile.downloads.core

import java.io.File
import java.io.IOException
import java.util.UUID

/** Free-space query, a platform boundary (StatFs on Android, a fake in tests). */
fun interface StorageSpace {
    fun usableBytes(dir: File): Long
}

/** Durably records a directory entry change (fsync of the directory), a platform boundary. */
fun interface DirectorySync {
    fun sync(dir: File)
}

/**
 * The app-controlled offline media store. Layout:
 *
 *     <root>/s<serverId>/u<userId>/<itemId>/<file>         completed, validated objects
 *     <root>/s<serverId>/u<userId>/<itemId>/.<file>.part   in-progress objects (never playable)
 *
 * Every account (server identity and user) owns a separate subtree, so removing an account
 * removes exactly its files. Nothing outside [root] is ever written or deleted.
 */
class OfflineStorage(
    val root: File,
    private val space: StorageSpace,
    private val directorySync: DirectorySync,
    /** Free space that downloads must always leave untouched. */
    val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
) {
    fun accountDir(serverId: Long, userId: Long): File = File(File(root, "s$serverId"), "u$userId")

    fun itemDir(serverId: Long, userId: Long, itemId: UUID): File = File(accountDir(serverId, userId), itemId.toString())

    fun finalFile(itemDir: File, fileName: String): File {
        require(isSafeName(fileName)) { "Unsafe file name $fileName" }
        return File(itemDir, fileName)
    }

    fun partFile(finalFile: File): File = File(finalFile.parentFile, ".${finalFile.name}$PART_SUFFIX")

    fun relativePath(file: File): String = file.canonicalFile.relativeTo(root.canonicalFile).path

    fun resolve(relativePath: String): File = File(root, relativePath).also { requireInside(it) }

    /** Free bytes that a download may still use. */
    fun availableBytes(dir: File = root): Long {
        val probe = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: root
        return (space.usableBytes(probe) - reserveBytes).coerceAtLeast(0)
    }

    /** Throws [InsufficientStorageException] unless [bytes] more can be written into [dir]. */
    fun ensureSpaceFor(bytes: Long, dir: File) {
        val available = availableBytes(dir)
        if (bytes > available) throw InsufficientStorageException(bytes, available)
    }

    /**
     * Atomically publishes a fully written and validated [part] file as [finalFile]: rename(2) within
     * one directory replaces the target in a single step, and the directory is synced afterwards so the
     * new name survives a power loss. Before this call the object only exists under its part name.
     */
    fun publish(part: File, finalFile: File) {
        requireInside(part)
        requireInside(finalFile)
        check(part.parentFile == finalFile.parentFile) { "Part and final file must share a directory" }
        if (!part.renameTo(finalFile)) throw IOException("Unable to publish ${part.name} as ${finalFile.name}")
        directorySync.sync(finalFile.parentFile!!)
    }

    /** Bytes used by everything under [dir] (completed and partial files). */
    fun usedBytes(dir: File = root): Long = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** Recursively deletes [dir], which must be inside [root]. Returns true if nothing is left. */
    fun deleteTree(dir: File): Boolean {
        requireInside(dir)
        check(dir.canonicalFile != root.canonicalFile) { "Refusing to delete the storage root" }
        if (!dir.exists()) return true
        dir.deleteRecursively()
        return !dir.exists()
    }

    fun prepareDir(dir: File) {
        requireInside(dir)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Unable to create $dir")
    }

    private fun requireInside(file: File) {
        val rootPath = root.canonicalFile.path + File.separator
        val path = file.canonicalFile.path
        require(path.startsWith(rootPath) || path == root.canonicalFile.path) { "$file is outside the offline store" }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val DEFAULT_RESERVE_BYTES = 256L * 1024 * 1024

        private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && !name.endsWith(PART_SUFFIX)
    }
}
