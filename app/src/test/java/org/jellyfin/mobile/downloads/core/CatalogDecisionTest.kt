package org.jellyfin.mobile.downloads.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.jupiter.api.Test
import java.util.UUID

class CatalogDecisionTest {
    private val itemId = UUID.randomUUID()

    private fun stream(type: MediaStreamType, codec: String, index: Int) = MediaStream(
        type = type,
        codec = codec,
        index = index,
        isExternal = false,
        isInterlaced = false,
        isDefault = false,
        isForced = false,
        isHearingImpaired = false,
        isTextSubtitleStream = false,
        supportsExternalStream = false,
    )

    private fun item(
        size: Long = 1_000,
        path: String = "/media/a.mp4",
        container: String = "mp4",
        video: String = "h264",
    ) =
        BaseItemDto(
            id = itemId,
            type = BaseItemKind.MOVIE,
            mediaSources = listOf(
                MediaSourceInfo(
                    protocol = MediaProtocol.FILE,
                    id = itemId.toString(),
                    path = path,
                    container = container,
                    size = size,
                    mediaStreams = listOf(
                        stream(MediaStreamType.VIDEO, video, 0),
                        stream(MediaStreamType.AUDIO, "aac", 1)
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

    @Test
    fun `an item deleted on the server keeps its offline copy and is marked gone`() {
        CatalogDecision.decide(item(), null) shouldBe CatalogDecision.Gone
    }

    @Test
    fun `replaced media is downloaded again`() {
        CatalogDecision.decide(item(), item(size = 2_000)).shouldBeInstanceOf<CatalogDecision.Replaced>()
        CatalogDecision.decide(item(), item(path = "/media/b.mp4")).shouldBeInstanceOf<CatalogDecision.Replaced>()
    }

    @Test
    fun `unchanged media and media that is no longer portable keep the offline copy`() {
        CatalogDecision.decide(item(), item()) shouldBe CatalogDecision.Unchanged
        CatalogDecision.decide(item(), item(video = "hevc", size = 3_000)) shouldBe CatalogDecision.Unchanged
    }
}
