package org.jellyfin.mobile.downloads.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException

class RangeDownloaderTest {
    private val content = TestMedia.mp4()
    private val origin = RangeServer(content)
    private val server = MockWebServer().apply {
        dispatcher = origin
        start()
    }
    private val dir = TestMedia.tempDir()
    private val space = FakeSpace()
    private val storage = OfflineStorage(dir, space, RecordingDirectorySync(), reserveBytes = 0)
    private val downloader = RangeDownloader(OkHttpClient(), storage)
    private val part = File(dir, ".media.mp4.part")
    private val url = server.url("/Items/x/Download").toString()

    private class Recorder : RangeDownloader.Listener {
        var total = -1L
        var validator: String? = null
        override suspend fun onStart(total: Long, validator: String?) {
            this.total = total
            this.validator = validator
        }
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun download(validator: String?, knownTotal: Long, listener: RangeDownloader.Listener = Recorder()) =
        runBlocking {
            downloader.download(
                RangeDownloader.Request(url, mapOf("Authorization" to "t"), part, validator, knownTotal),
                listener
            )
        }

    @Test
    fun `fresh download writes the whole object and reports its validator`() {
        val result = download(null, -1)
        result shouldBe RangeDownloader.Result(content.size.toLong(), "\"v1\"")
        part.readBytes().contentEquals(content) shouldBe true
        origin.requests.single().getHeader("Authorization") shouldBe "t"
    }

    @Test
    fun `interrupted download keeps its bytes and resumes with a range request`() {
        origin.cutNextResponseAfter = 100_000
        val recorder = Recorder()
        shouldThrow<IOException> { download(null, -1, recorder) }
        part.length() shouldBe 100_000L

        val result = download(recorder.validator, recorder.total)

        result.total shouldBe content.size.toLong()
        part.readBytes().contentEquals(content) shouldBe true
        val resume = origin.requests.last()
        resume.getHeader("Range") shouldBe "bytes=100000-"
        resume.getHeader("If-Range") shouldBe "\"v1\""
    }

    @Test
    fun `a replaced server object is downloaded from zero instead of being spliced`() {
        origin.cutNextResponseAfter = 100_000
        val recorder = Recorder()
        shouldThrow<IOException> { download(null, -1, recorder) }

        val replacement = TestMedia.mp4(mdatSize = 250_000, seed = 9)
        origin.content = replacement
        origin.etag = "\"v2\""
        val result = download(recorder.validator, recorder.total)

        result shouldBe RangeDownloader.Result(replacement.size.toLong(), "\"v2\"")
        part.readBytes().contentEquals(replacement) shouldBe true
    }

    @Test
    fun `a server that ignores the range restarts the object from zero`() {
        origin.cutNextResponseAfter = 100_000
        val recorder = Recorder()
        shouldThrow<IOException> { download(null, -1, recorder) }
        origin.ignoreRange = true

        download(recorder.validator, recorder.total)

        part.readBytes().contentEquals(content) shouldBe true
    }

    @Test
    fun `without a validator a partial file is never resumed`() {
        origin.etag = null
        origin.cutNextResponseAfter = 100_000
        shouldThrow<IOException> { download(null, -1) }

        download(null, -1)

        origin.requests.last().getHeader("Range") shouldBe null
        part.readBytes().contentEquals(content) shouldBe true
    }

    @Test
    fun `an already complete partial file is accepted on 416`() {
        part.writeBytes(content)
        val result = download("\"v1\"", content.size.toLong())
        result.total shouldBe content.size.toLong()
        origin.requests.single().getHeader("Range") shouldBe "bytes=${content.size}-"
    }

    @Test
    fun `a partial file longer than the object is discarded`() {
        part.writeBytes(content + ByteArray(10))
        download("\"v1\"", content.size.toLong() + 10)
        part.readBytes().contentEquals(content) shouldBe true
    }

    @Test
    fun `low storage fails before writing`() {
        space.usable = 1_000
        shouldThrow<InsufficientStorageException> { download(null, -1) }
        (part.exists() && part.length() > 0) shouldBe false
    }

    @Test
    fun `rejected credentials and missing items are permanent failures`() {
        origin.failNextWith = 401
        shouldThrow<AuthExpiredException> { download(null, -1) }
        origin.failNextWith = 404
        shouldThrow<ServerItemGoneException> { download(null, -1) }
        origin.failNextWith = 503
        val error = shouldThrow<IOException> { download(null, -1) }
        error shouldNotBe null
    }
}
