package org.jellyfin.mobile.downloads.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jellyfin.mobile.downloads.DownloadFileType
import org.junit.jupiter.api.Test
import java.io.File

class MediaValidatorTest {
    private val dir = TestMedia.tempDir()

    private fun write(bytes: ByteArray) = File(dir, "f-${System.nanoTime()}").apply { writeBytes(bytes) }

    @Test
    fun `complete mp4 is valid`() {
        val bytes = TestMedia.mp4()
        MediaValidator.validate(write(bytes), DownloadFileType.ITEM, bytes.size.toLong()) shouldBe MediaValidator.Result.Valid
    }

    @Test
    fun `truncated mp4 is rejected even when the expected size matches the truncated length`() {
        val bytes = TestMedia.mp4().copyOf(200_000)
        MediaValidator.validate(write(bytes), DownloadFileType.ITEM, bytes.size.toLong())
            .shouldBeInstanceOf<MediaValidator.Result.Invalid>()
    }

    @Test
    fun `partial file is rejected against the announced size`() {
        val bytes = TestMedia.mp4()
        MediaValidator.validate(write(bytes.copyOf(bytes.size - 1)), DownloadFileType.ITEM, bytes.size.toLong())
            .shouldBeInstanceOf<MediaValidator.Result.Invalid>()
    }

    @Test
    fun `trailing garbage is rejected`() {
        val bytes = TestMedia.mp4() + ByteArray(5) { 7 }
        MediaValidator.validate(write(bytes), DownloadFileType.ITEM, bytes.size.toLong())
            .shouldBeInstanceOf<MediaValidator.Result.Invalid>()
    }

    @Test
    fun `corrupt object of the right length is rejected`() {
        val bytes = ByteArray(TestMedia.mp4().size) { 0x41 }
        MediaValidator.validate(write(bytes), DownloadFileType.ITEM, bytes.size.toLong())
            .shouldBeInstanceOf<MediaValidator.Result.Invalid>()
    }

    @Test
    fun `mp4 without moov is rejected`() {
        val bytes = TestMedia.box("ftyp", ByteArray(8)) + TestMedia.box("mdat", ByteArray(64))
        MediaValidator.validate(write(bytes), DownloadFileType.ITEM, bytes.size.toLong())
            .shouldBeInstanceOf<MediaValidator.Result.Invalid>()
    }

    @Test
    fun `images and subtitles are checked by content`() {
        val webp = TestMedia.webp()
        MediaValidator.validate(write(webp), DownloadFileType.IMAGE_PRIMARY, webp.size.toLong()) shouldBe MediaValidator.Result.Valid
        val html = "<html>error</html>".toByteArray()
        MediaValidator.validate(write(html), DownloadFileType.IMAGE_PRIMARY, html.size.toLong())
            .shouldBeInstanceOf<MediaValidator.Result.Invalid>()
        val srt = TestMedia.srt()
        MediaValidator.validate(write(srt), DownloadFileType.SUBTITLE, srt.size.toLong()) shouldBe MediaValidator.Result.Valid
        val binary = byteArrayOf(1, 0, 2)
        MediaValidator.validate(write(binary), DownloadFileType.SUBTITLE, 3).shouldBeInstanceOf<MediaValidator.Result.Invalid>()
    }
}
