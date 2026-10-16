package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.core.DownloadJob
import org.jellyfin.mobile.downloads.core.OfflineStorage
import org.jellyfin.mobile.downloads.core.PlannedFile
import org.jellyfin.mobile.downloads.core.PortableMediaPolicy
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUID

/** Turns a download row into the concrete objects to fetch, using the account's own API client. */
class DownloadPlanner(private val storage: OfflineStorage) {
    sealed interface Plan {
        data class Ready(val job: DownloadJob) : Plan
        data class NotPortable(val reason: String) : Plan
    }

    fun plan(download: DownloadEntity, api: ApiClient): Plan {
        val item = download.item
        val decision = PortableMediaPolicy.decide(item)
        if (decision is PortableMediaPolicy.Decision.NotPortable) return Plan.NotPortable(decision.reason)
        decision as PortableMediaPolicy.Decision.Portable

        val source = decision.mediaSource
        val sourceItemId = source.id?.toUUID() ?: item.id
        val files = buildList {
            item.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                add(
                    PlannedFile(
                        type = DownloadFileType.IMAGE_PRIMARY,
                        fileName = PRIMARY_IMAGE_FILE,
                        url = api.imageApi.getItemImageUrl(
                            itemId = item.id,
                            imageType = ImageType.PRIMARY,
                            tag = tag,
                            format = ImageFormat.WEBP,
                        ),
                    ),
                )
            }
            add(PlannedFile(DownloadFileType.ITEM, MEDIA_FILE, api.libraryApi.getDownloadUrl(sourceItemId)))
            for (stream in decision.externalSubtitles) {
                val format = subtitleExtension(stream.codec.orEmpty())
                add(
                    PlannedFile(
                        type = DownloadFileType.SUBTITLE,
                        fileName = subtitleFileName(stream.index, format),
                        url = api.subtitleApi.getSubtitleUrl(
                            routeItemId = sourceItemId,
                            routeMediaSourceId = source.id.orEmpty(),
                            routeIndex = stream.index,
                            routeFormat = format,
                        ),
                        streamIndex = stream.index,
                    ),
                )
            }
        }

        val authorization = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )
        return Plan.Ready(
            DownloadJob(
                downloadId = download.id,
                itemDir = storage.itemDir(download.serverId, download.userId, download.itemId),
                headers = mapOf("Authorization" to authorization),
                files = files,
            ),
        )
    }

    companion object {
        const val MEDIA_FILE = "media.mp4"
        const val PRIMARY_IMAGE_FILE = "primary.img"

        fun subtitleExtension(codec: String): String = when (codec.lowercase()) {
            "subrip", "srt" -> "srt"
            "webvtt", "vtt" -> "vtt"
            "ass" -> "ass"
            "ssa" -> "ssa"
            else -> "srt"
        }

        fun subtitleFileName(index: Int, extension: String) = "subtitle-$index.$extension"
    }
}
