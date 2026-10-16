package org.jellyfin.mobile.downloads

import android.net.Uri
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.core.FakeSpace
import org.jellyfin.mobile.downloads.core.OfflineStorage
import org.jellyfin.mobile.downloads.core.RecordingDirectorySync
import org.jellyfin.mobile.downloads.core.TestMedia
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class OfflineLibraryTest {
    private val storage = OfflineStorage(TestMedia.tempDir(), FakeSpace(), RecordingDirectorySync(), reserveBytes = 0)
    private val library = OfflineLibrary(mockk<DownloadDao>(), storage) { 1L to 1L }
    private val itemId = UUID.randomUUID()
    private val itemDir = storage.itemDir(1, 1, itemId).apply { mkdirs() }
    private val media = TestMedia.mp4()
    private val mediaFile = File(itemDir, DownloadPlanner.MEDIA_FILE).apply { writeBytes(media) }
    private val subtitleFile = File(itemDir, "subtitle-3.srt").apply { writeBytes(TestMedia.srt()) }

    private fun stream(type: MediaStreamType, codec: String, index: Int, external: Boolean = false) = MediaStream(
        type = type,
        codec = codec,
        index = index,
        isExternal = external,
        isInterlaced = false,
        isDefault = false,
        isForced = false,
        isHearingImpaired = false,
        isTextSubtitleStream = type == MediaStreamType.SUBTITLE,
        supportsExternalStream = external,
    )

    private val item = BaseItemDto(
        id = itemId,
        type = BaseItemKind.MOVIE,
        mediaSources = listOf(
            MediaSourceInfo(
                protocol = MediaProtocol.FILE,
                id = itemId.toString(),
                container = "mp4",
                size = media.size.toLong(),
                mediaStreams = listOf(
                    stream(MediaStreamType.VIDEO, "h264", 0),
                    stream(MediaStreamType.AUDIO, "aac", 1),
                    stream(MediaStreamType.AUDIO, "aac", 2),
                    stream(MediaStreamType.SUBTITLE, "subrip", 3, external = true),
                ),
                type = MediaSourceType.DEFAULT,
                isRemote = false,
                readAtNativeFramerate = false,
                ignoreDts = false,
                ignoreIndex = false,
                genPtsInput = false,
                supportsTranscoding = true,
                supportsDirectStream = true,
                supportsDirectPlay = true,
                isInfiniteStream = false,
                requiresOpening = false,
                requiresClosing = false,
                requiresLooping = false,
                supportsProbing = true,
                transcodingSubProtocol = MediaStreamProtocol.HTTP,
                hasSegments = false,
            ),
        ),
    )

    private fun file(type: DownloadFileType, file: File, status: DownloadStatus, total: Long, index: Int? = null) =
        DownloadFileEntity(
            downloadId = 1,
            type = type,
            size = total,
            fileName = file.name,
            uri = mockk<Uri>(),
            status = status,
            totalBytes = total,
            streamIndex = index,
            localPath = storage.relativePath(file),
        )

    private fun download(
        status: DownloadStatus,
        mediaStatus: DownloadStatus = status,
        mediaTotal: Long = media.size.toLong(),
    ) =
        DownloadFiles(
            DownloadEntity(id = 1, serverId = 1, userId = 1, itemId = itemId, path = "p", item = item, status = status),
            listOf(
                file(DownloadFileType.ITEM, mediaFile, mediaStatus, mediaTotal),
                file(
                    DownloadFileType.SUBTITLE,
                    subtitleFile,
                    DownloadStatus.DOWNLOADED,
                    subtitleFile.length(),
                    index = 3
                ),
            ),
        )

    @Test
    fun `a complete download plays from its local media and subtitle files`() {
        val playable = library.toPlayable(download(DownloadStatus.DOWNLOADED)).shouldNotBeNull()
        playable.mediaFile shouldBe mediaFile
        playable.subtitleFiles shouldBe mapOf(3 to subtitleFile)
        library.isIntact(playable) shouldBe true
    }

    @Test
    fun `a partial download is never playable, even if bytes exist on disk`() {
        library.toPlayable(download(DownloadStatus.DOWNLOADING)).shouldBeNull()
        library.toPlayable(download(DownloadStatus.PAUSED)).shouldBeNull()
        library.toPlayable(download(DownloadStatus.DOWNLOADED, mediaStatus = DownloadStatus.DOWNLOADING)).shouldBeNull()
    }

    @Test
    fun `a truncated or missing media file is not playable`() {
        library.toPlayable(download(DownloadStatus.DOWNLOADED, mediaTotal = media.size + 1L)).shouldBeNull()
        mediaFile.delete()
        library.toPlayable(download(DownloadStatus.DOWNLOADED)).shouldBeNull()
    }
}
