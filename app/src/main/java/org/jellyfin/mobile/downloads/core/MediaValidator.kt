package org.jellyfin.mobile.downloads.core

import org.jellyfin.mobile.downloads.DownloadFileType
import java.io.File
import java.io.RandomAccessFile

/**
 * Validates a completely received object before it may be published to the Downloads library.
 * Checks the exact size announced by the server and, per type, the structure of the content, so a
 * truncated, padded or foreign object is rejected even when its length happens to match.
 */
object MediaValidator {
    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(file: File, type: DownloadFileType, expectedSize: Long): Result {
        if (!file.isFile) return Result.Invalid("missing")
        val length = file.length()
        if (expectedSize < 0 || length != expectedSize) return Result.Invalid("size $length, expected $expectedSize")
        if (length == 0L) return Result.Invalid("empty")
        return when (type) {
            DownloadFileType.ITEM -> validateMp4(file)
            DownloadFileType.IMAGE_PRIMARY -> validateImage(file)
            DownloadFileType.SUBTITLE -> validateText(file)
        }
    }

    /**
     * Walks the top-level ISO BMFF boxes: they must tile the file exactly, start with `ftyp`, and include
     * `moov` and `mdat`. Truncation, trailing garbage and non-MP4 content all fail.
     */
    fun validateMp4(file: File): Result = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val seen = mutableListOf<String>()
        var position = 0L
        while (position < length) {
            when (val box = readBox(raf, position, length)) {
                is BoxRead.Bad -> return Result.Invalid(box.reason)
                is BoxRead.Box -> {
                    seen += box.type
                    position += box.size
                }
            }
        }
        when {
            seen.firstOrNull() != "ftyp" -> Result.Invalid("first box is not ftyp")
            "moov" !in seen -> Result.Invalid("no moov box")
            "mdat" !in seen -> Result.Invalid("no mdat box")
            else -> Result.Valid
        }
    }

    private sealed interface BoxRead {
        data class Box(val type: String, val size: Long) : BoxRead
        data class Bad(val reason: String) : BoxRead
    }

    /** Reads the box header at [position]; the box must lie entirely inside the file. */
    private fun readBox(raf: RandomAccessFile, position: Long, length: Long): BoxRead {
        if (length - position < BOX_HEADER) return BoxRead.Bad("truncated box header at $position")
        val header = ByteArray(BOX_HEADER)
        raf.seek(position)
        raf.readFully(header)
        val type = String(header, TYPE_OFFSET, TYPE_LENGTH, Charsets.ISO_8859_1)
        if (!type.all { it in BOX_TYPE_CHARS }) return BoxRead.Bad("bad box type at $position")
        val size = when (val small = readUInt32(header, 0)) {
            0L -> length - position
            1L -> if (length - position < BOX_HEADER + LARGE_SIZE_LENGTH) -1L else raf.readLong()
            else -> small
        }
        return if (size < BOX_HEADER || position + size > length) {
            BoxRead.Bad("box $type overruns the file")
        } else {
            BoxRead.Box(type, size)
        }
    }

    private fun validateImage(file: File): Result {
        val head = file.inputStream().use { it.readNBytesCompat(IMAGE_MAGIC_LENGTH) }
        val webp = head.size >= IMAGE_MAGIC_LENGTH &&
            String(head, 0, RIFF_LENGTH, Charsets.ISO_8859_1) == "RIFF" &&
            String(head, WEBP_OFFSET, RIFF_LENGTH, Charsets.ISO_8859_1) == "WEBP"
        val jpeg = head.size >= 2 && head[0] == JPEG_0 && head[1] == JPEG_1
        val png = head.size >= PNG_MAGIC.size && head.copyOf(PNG_MAGIC.size).contentEquals(PNG_MAGIC)
        return if (webp || jpeg || png) Result.Valid else Result.Invalid("not a WebP, JPEG or PNG image")
    }

    private fun validateText(file: File): Result {
        val sample = file.inputStream().use { it.readNBytesCompat(TEXT_SAMPLE_LENGTH) }
        return if (sample.any { it == 0.toByte() }) Result.Invalid("binary data in a text subtitle") else Result.Valid
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (0 until UINT32_LENGTH).fold(
            0L
        ) { acc, i -> (acc shl BITS_PER_BYTE) or (bytes[offset + i].toLong() and BYTE_MASK) }

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private const val BOX_HEADER = 8
    private const val TYPE_OFFSET = 4
    private const val TYPE_LENGTH = 4
    private const val LARGE_SIZE_LENGTH = 8
    private const val UINT32_LENGTH = 4
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFFL
    private const val IMAGE_MAGIC_LENGTH = 12
    private const val RIFF_LENGTH = 4
    private const val WEBP_OFFSET = 8
    private const val JPEG_0 = 0xFF.toByte()
    private const val JPEG_1 = 0xD8.toByte()
    private const val TEXT_SAMPLE_LENGTH = 64 * 1024
    private val PNG_MAGIC = "\u0089PNG\r\n\u001a\n".toByteArray(Charsets.ISO_8859_1)
    private val BOX_TYPE_CHARS = (' '..'~').toSet()
}
