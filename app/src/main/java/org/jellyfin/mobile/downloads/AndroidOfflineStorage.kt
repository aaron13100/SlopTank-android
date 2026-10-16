package org.jellyfin.mobile.downloads

import android.content.Context
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.jellyfin.mobile.downloads.core.DirectorySync
import org.jellyfin.mobile.downloads.core.OfflineStorage
import org.jellyfin.mobile.downloads.core.StorageSpace
import timber.log.Timber
import java.io.File

/**
 * Creates the offline store in the app's private internal storage (`files/offline`). It needs no storage
 * permission, is invisible to other apps and media scanners, and is removed when the app is uninstalled.
 */
fun createOfflineStorage(context: Context): OfflineStorage = OfflineStorage(
    root = File(context.filesDir, "offline").apply { mkdirs() },
    space = StatFsStorageSpace,
    directorySync = OsDirectorySync,
)

private object StatFsStorageSpace : StorageSpace {
    override fun usableBytes(dir: File): Long = StatFs(dir.path).availableBytes
}

private object OsDirectorySync : DirectorySync {
    override fun sync(dir: File) {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            // Some file systems refuse fsync on directories; the rename itself is still atomic.
            Timber.w(e, "Unable to sync directory %s", dir)
        }
    }
}
