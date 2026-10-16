package org.jellyfin.mobile.downloads.core

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * Decides whether an item can be downloaded for offline playback without any server transcode.
 * Only the portable-media invariant qualifies: a local MP4 file with H.264 video and AAC audio, played
 * directly. Anything else is reported honestly as not available offline; nothing is ever transcoded.
 */
object PortableMediaPolicy {
    sealed interface Decision {
        data class Portable(
            val mediaSource: MediaSourceInfo,
            /** External text subtitles that are downloaded next to the media file. */
            val externalSubtitles: List<MediaStream>,
        ) : Decision

        data class NotPortable(val reason: String) : Decision
    }

    private val SUPPORTED_KINDS = setOf(
        BaseItemKind.MOVIE,
        BaseItemKind.EPISODE,
        BaseItemKind.VIDEO,
        BaseItemKind.MUSIC_VIDEO
    )
    private val MP4_CONTAINERS = setOf("mp4", "m4v", "mov")
    private val TEXT_SUBTITLE_CODECS = setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")
    private val EMBEDDED_SUBTITLE_CODECS = setOf("mov_text", "tx3g")

    fun decide(item: BaseItemDto): Decision {
        if (item.type !in SUPPORTED_KINDS) return Decision.NotPortable("item type ${item.type} is not a video")
        val sources = item.mediaSources.orEmpty()
        if (sources.isEmpty()) return Decision.NotPortable("no media source")
        val reasons = mutableListOf<String>()
        for (source in sources) {
            when (val reason = notPortableReason(source)) {
                null -> return Decision.Portable(
                    mediaSource = source,
                    externalSubtitles = source.mediaStreams.orEmpty().filter { stream ->
                        stream.type == MediaStreamType.SUBTITLE && stream.isExternal &&
                            stream.codec?.lowercase() in TEXT_SUBTITLE_CODECS
                    },
                )
                else -> reasons += reason
            }
        }
        return Decision.NotPortable(reasons.joinToString("; "))
    }

    /** Returns null if [source] is portable, else a human-readable reason. */
    fun notPortableReason(source: MediaSourceInfo): String? {
        if (source.protocol != MediaProtocol.FILE || source.isRemote) return "not a local file"
        if ((source.size ?: 0L) <= 0L) return "unknown file size"
        val containers = source.container.orEmpty().lowercase().split(',').map(String::trim)
        if (containers.none { it in MP4_CONTAINERS }) return "container ${source.container} is not MP4"
        val streams = source.mediaStreams.orEmpty()
        val video = streams.filter { it.type == MediaStreamType.VIDEO }
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        val embeddedSubtitles = streams.filter { it.type == MediaStreamType.SUBTITLE && !it.isExternal }
        return when {
            video.isEmpty() -> "no video stream"
            video.any { it.codec?.lowercase() != "h264" } -> "video is not H.264"
            audio.isEmpty() -> "no audio stream"
            audio.any { it.codec?.lowercase() != "aac" } -> "audio is not AAC"
            embeddedSubtitles.any { it.codec?.lowercase() !in EMBEDDED_SUBTITLE_CODECS } ->
                "embedded subtitles are not MP4 text"
            else -> null
        }
    }
}
