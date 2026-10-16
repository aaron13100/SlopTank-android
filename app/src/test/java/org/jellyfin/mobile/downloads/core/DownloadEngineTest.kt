package org.jellyfin.mobile.downloads.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class DownloadEngineTest {
    private val media = TestMedia.mp4(mdatSize = 2_000_000)
    private val image = TestMedia.webp()
    private val mediaOrigin = RangeServer(media)
    private val imageOrigin = RangeServer(image, "\"img\"")
    private val mediaServer = MockWebServer().apply { dispatcher = mediaOrigin; start() }
    private val imageServer = MockWebServer().apply { dispatcher = imageOrigin; start() }
    private val root = TestMedia.tempDir()
    private val space = FakeSpace()
    private val sync = RecordingDirectorySync()
    private val storage = OfflineStorage(root, space, sync, reserveBytes = 0)
    private val records = FakeRecords().apply { add(DOWNLOAD_ID) }
    private val engine = DownloadEngine(records, storage, RangeDownloader(OkHttpClient(), storage), clock = { 0L })
    private val itemDir = storage.itemDir(1, 1, UUID.fromString("00000000-0000-0000-0000-000000000042"))
    private val job = DownloadJob(
        downloadId = DOWNLOAD_ID,
        itemDir = itemDir,
        headers = emptyMap(),
        files = listOf(
            PlannedFile(DownloadFileType.IMAGE_PRIMARY, "primary.webp", imageServer.url("/img").toString()),
            PlannedFile(DownloadFileType.ITEM, "media.mp4", mediaServer.url("/media").toString()),
        ),
    )
    private val mediaFile = File(itemDir, "media.mp4")

    @AfterEach
    fun tearDown() {
        mediaServer.shutdown()
        imageServer.shutdown()
    }

    private fun run() = runBlocking { engine.run(job) }

    private fun partFiles() = itemDir.listFiles().orEmpty().filter { it.name.endsWith(OfflineStorage.PART_SUFFIX) }

    @Test
    fun `a completed download is validated, published atomically and recorded`() {
        run() shouldBe EngineOutcome.COMPLETED
        records.statuses[DOWNLOAD_ID] shouldBe DownloadStatus.DOWNLOADED
        mediaFile.readBytes().contentEquals(media) shouldBe true
        partFiles().shouldBeEmpty()
        sync.synced.toSet() shouldBe setOf(itemDir)
        records.paths.values.toSet() shouldBe setOf("s1/u1/${itemDir.name}/primary.webp", "s1/u1/${itemDir.name}/media.mp4")
    }

    @Test
    fun `an interrupted download is never playable and resumes where it stopped`() {
        mediaOrigin.cutNextResponseAfter = 120_000
        run() shouldBe EngineOutcome.RETRY_LATER
        records.statuses[DOWNLOAD_ID] shouldBe DownloadStatus.QUEUED
        records.reasons[DOWNLOAD_ID] shouldBe DownloadErrorReason.NETWORK
        mediaFile.exists() shouldBe false
        partFiles().single().length() shouldBe 120_000L

        run() shouldBe EngineOutcome.COMPLETED
        mediaOrigin.requests.last().getHeader("Range") shouldBe "bytes=120000-"
        mediaFile.readBytes().contentEquals(media) shouldBe true
    }

    @Test
    fun `a corrupt object is discarded and never published`() {
        mediaOrigin.content = ByteArray(media.size) { 0x55 }
        run() shouldBe EngineOutcome.FAILED
        records.statuses[DOWNLOAD_ID] shouldBe DownloadStatus.ERROR
        records.reasons[DOWNLOAD_ID] shouldBe DownloadErrorReason.VALIDATION_FAILED
        mediaFile.exists() shouldBe false
        partFiles().shouldBeEmpty()
    }

    @Test
    fun `retrying a completed download is a no-op without network traffic`() {
        run() shouldBe EngineOutcome.COMPLETED
        val requests = mediaOrigin.requests.size + imageOrigin.requests.size
        records.statuses[DOWNLOAD_ID] = DownloadStatus.QUEUED

        run() shouldBe EngineOutcome.COMPLETED
        mediaOrigin.requests.size + imageOrigin.requests.size shouldBe requests
    }

    @Test
    fun `a published file that went missing is downloaded again`() {
        run() shouldBe EngineOutcome.COMPLETED
        mediaFile.delete()
        records.statuses[DOWNLOAD_ID] = DownloadStatus.QUEUED

        run() shouldBe EngineOutcome.COMPLETED
        mediaFile.readBytes().contentEquals(media) shouldBe true
    }

    @Test
    fun `a kill between publish and record update is finished without downloading again`() {
        run() shouldBe EngineOutcome.COMPLETED
        val requests = mediaOrigin.requests.size
        // Simulate the crash window: file published, record still says downloading.
        val id = records.files.values.first { it.type == DownloadFileType.ITEM }.id
        records.files[id] = records.files.getValue(id).copy(status = DownloadStatus.DOWNLOADING)
        records.statuses[DOWNLOAD_ID] = DownloadStatus.QUEUED

        run() shouldBe EngineOutcome.COMPLETED
        mediaOrigin.requests.size shouldBe requests
    }

    @Test
    fun `pausing keeps the partial data and the paused status`() {
        records.onProgress = { it.statuses[DOWNLOAD_ID] = DownloadStatus.PAUSED }
        run() shouldBe EngineOutcome.INTERRUPTED
        records.statuses[DOWNLOAD_ID] shouldBe DownloadStatus.PAUSED
        mediaFile.exists() shouldBe false

        records.onProgress = {}
        records.statuses[DOWNLOAD_ID] = DownloadStatus.QUEUED
        run() shouldBe EngineOutcome.COMPLETED
        mediaFile.readBytes().contentEquals(media) shouldBe true
    }

    @Test
    fun `low storage fails with a reason and publishes nothing`() {
        space.usable = 10_000
        run() shouldBe EngineOutcome.FAILED
        records.reasons[DOWNLOAD_ID] shouldBe DownloadErrorReason.INSUFFICIENT_STORAGE
        mediaFile.exists() shouldBe false
    }

    @Test
    fun `a removed download is not resurrected by a running worker`() {
        records.onProgress = { it.statuses.remove(DOWNLOAD_ID) }
        run() shouldBe EngineOutcome.INTERRUPTED
        records.statuses[DOWNLOAD_ID] shouldBe null
    }

    @Test
    fun `server item deletion is reported as such`() {
        mediaOrigin.failNextWith = 404
        run() shouldBe EngineOutcome.FAILED
        records.reasons[DOWNLOAD_ID] shouldBe DownloadErrorReason.SERVER_ITEM_GONE
    }

    private companion object {
        const val DOWNLOAD_ID = 7L
    }
}
