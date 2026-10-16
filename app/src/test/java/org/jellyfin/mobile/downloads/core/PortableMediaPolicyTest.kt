package org.jellyfin.mobile.downloads.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.jupiter.api.Test
import java.util.UUID

class PortableMediaPolicyTest {
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

    private fun source(container: String, streams: List<MediaStream>, protocol: MediaProtocol = MediaProtocol.FILE) =
        MediaSourceInfo(
            protocol = protocol,
            id = "abc",
            container = container,
            size = 1_000_000,
            mediaStreams = streams,
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
            transcodingSubProtocol = org.jellyfin.sdk.model.api.MediaStreamProtocol.HTTP,
            hasSegments = false,
        )

    private fun item(vararg sources: MediaSourceInfo) = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.MOVIE,
        mediaSources = sources.toList(),
    )

    private val portableStreams = listOf(
        stream(MediaStreamType.VIDEO, "h264", 0),
        stream(MediaStreamType.AUDIO, "aac", 1),
        stream(MediaStreamType.AUDIO, "aac", 2),
        stream(MediaStreamType.SUBTITLE, "subrip", 3, external = true),
        stream(MediaStreamType.SUBTITLE, "PGSSUB", 4, external = true),
    )

    @Test
    fun `h264 aac mp4 is portable and keeps its external text subtitles`() {
        val decision = PortableMediaPolicy.decide(item(source("mov,mp4,m4a,3gp,3g2,mj2", portableStreams)))
        decision.shouldBeInstanceOf<PortableMediaPolicy.Decision.Portable>()
        decision.externalSubtitles shouldHaveSize 1
        decision.externalSubtitles.single().index shouldBe 3
    }

    @Test
    fun `anything that would need a transcode is not portable`() {
        val hevc = portableStreams.map { if (it.index == 0) it.copy(codec = "hevc") else it }
        val ac3 = portableStreams.map { if (it.index == 2) it.copy(codec = "ac3") else it }
        listOf(
            source("matroska,webm", portableStreams),
            source("mp4", hevc),
            source("mp4", ac3),
            source("mp4", portableStreams, protocol = MediaProtocol.HTTP),
        ).forEach { source ->
            PortableMediaPolicy.decide(item(source)).shouldBeInstanceOf<PortableMediaPolicy.Decision.NotPortable>()
        }
    }

    @Test
    fun `a portable alternate version is chosen over a non-portable primary`() {
        val mkv = source("matroska", portableStreams).copy(id = "mkv")
        val mp4 = source("mp4", portableStreams).copy(id = "mp4")
        val decision = PortableMediaPolicy.decide(item(mkv, mp4))
        decision.shouldBeInstanceOf<PortableMediaPolicy.Decision.Portable>()
        decision.mediaSource.id shouldBe "mp4"
    }

    @Test
    fun `non-video items are not portable`() {
        PortableMediaPolicy.decide(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.AUDIO))
            .shouldBeInstanceOf<PortableMediaPolicy.Decision.NotPortable>()
    }
}
