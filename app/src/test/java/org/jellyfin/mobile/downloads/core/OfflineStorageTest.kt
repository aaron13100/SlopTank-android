package org.jellyfin.mobile.downloads.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class OfflineStorageTest {
    private val sandbox = TestMedia.tempDir()
    private val root = File(sandbox, "offline").apply { mkdirs() }
    private val outside = File(sandbox, "library/movie.mp4").apply {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }
    private val storage = OfflineStorage(root, FakeSpace(), RecordingDirectorySync(), reserveBytes = 0)

    @Test
    fun `removal deletes only the item's own files`() {
        val item = storage.itemDir(1, 1, UUID.randomUUID()).apply { mkdirs() }
        val sibling = storage.itemDir(1, 1, UUID.randomUUID()).apply { mkdirs() }
        File(item, "media.mp4").writeBytes(ByteArray(10))
        File(sibling, "media.mp4").writeBytes(ByteArray(20))

        storage.deleteTree(item) shouldBe true
        item.exists() shouldBe false
        File(sibling, "media.mp4").length() shouldBe 20L
        outside.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
        storage.usedBytes() shouldBe 20L
    }

    @Test
    fun `nothing outside the store can be deleted or published`() {
        shouldThrow<IllegalArgumentException> { storage.deleteTree(outside.parentFile) }
        shouldThrow<IllegalArgumentException> { storage.deleteTree(File(root, "../library")) }
        shouldThrow<IllegalStateException> { storage.deleteTree(root) }
        shouldThrow<IllegalArgumentException> { storage.publish(outside, File(root, "x.mp4")) }
        outside.exists() shouldBe true
    }

    @Test
    fun `unsafe file names are refused`() {
        listOf("../escape.mp4", "a/b.mp4", ".hidden", "x.mp4.part", "").forEach { name ->
            shouldThrow<IllegalArgumentException> { storage.finalFile(root, name) }
        }
    }

    @Test
    fun `accounts are separate subtrees`() {
        val a = storage.accountDir(1, 1)
        val b = storage.accountDir(1, 2)
        val c = storage.accountDir(2, 1)
        setOf(a, b, c).size shouldBe 3
        a.toPath().startsWith(b.toPath()) shouldBe false
        b.toPath().startsWith(a.toPath()) shouldBe false
    }
}
