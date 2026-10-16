package org.jellyfin.mobile.downloads.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** Small, structurally valid media objects for tests. */
object TestMedia {
    fun box(type: String, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 + payload.size)
        buffer.putInt(8 + payload.size)
        buffer.put(type.toByteArray(Charsets.ISO_8859_1))
        buffer.put(payload)
        return buffer.array()
    }

    /** A minimal ISO BMFF file: ftyp, moov, mdat with [mdatSize] bytes of deterministic payload. */
    fun mp4(mdatSize: Int = 300_000, seed: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "isom\u0000\u0000\u0002\u0000isomiso2avc1mp41".toByteArray(Charsets.ISO_8859_1)))
        out.write(box("moov", box("mvhd", ByteArray(100) { it.toByte() })))
        out.write(box("mdat", ByteArray(mdatSize) { ((it * 31 + seed) and 0xFF).toByte() }))
        return out.toByteArray()
    }

    fun webp(size: Int = 64): ByteArray {
        val bytes = ByteArray(size)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        return bytes
    }

    fun srt(): ByteArray = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()

    fun tempDir(): File = kotlin.io.path.createTempDirectory("c203-test").toFile()
}

/** Free space that a test controls. */
class FakeSpace(var usable: Long = Long.MAX_VALUE / 4) : StorageSpace {
    override fun usableBytes(dir: File): Long = usable
}

class RecordingDirectorySync : DirectorySync {
    val synced = mutableListOf<File>()
    override fun sync(dir: File) {
        synced += dir
    }
}
