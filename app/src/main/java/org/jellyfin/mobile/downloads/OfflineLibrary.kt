package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.core.MediaValidator
import org.jellyfin.mobile.downloads.core.OfflineStorage
import org.jellyfin.mobile.downloads.core.PortableMediaPolicy
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.io.File
import java.util.UUID

/** A download that is complete and whose files are present on this device. */
data class PlayableDownload(
    val download: DownloadEntity,
    val mediaSource: MediaSourceInfo,
    val mediaFile: File,
    /** External subtitle files keyed by media stream index. */
    val subtitleFiles: Map<Int, File>,
    val imageFile: File?,
)

/**
 * Read access to the current account's offline catalogue. Works entirely from Room and local files, so it
 * never touches the network.
 */
class OfflineLibrary(
    private val downloadDao: DownloadDao,
    private val storage: OfflineStorage,
    private val appPreferences: AppPreferences,
) {
    /** The current account as (local server id, local user id), or null if none is selected. */
    fun currentAccount(): Pair<Long, Long>? {
        val server = appPreferences.currentServerId ?: return null
        val user = appPreferences.currentUserId ?: return null
        return server to user
    }

    suspend fun findPlayable(itemId: UUID): PlayableDownload? {
        val (serverId, userId) = currentAccount() ?: return null
        val downloadFiles = downloadDao.getDownloadByItemId(serverId, userId, itemId) ?: return null
        return toPlayable(downloadFiles)
    }

    /**
     * Returns the playable view of [downloadFiles], or null unless it is complete and its media file is present
     * with the recorded size. A partial or missing object is never returned.
     */
    fun toPlayable(downloadFiles: DownloadFiles): PlayableDownload? {
        val (download, files) = downloadFiles
        if (download.status != DownloadStatus.DOWNLOADED) return null
        val decision = PortableMediaPolicy.decide(download.item) as? PortableMediaPolicy.Decision.Portable
            ?: return null
        val media = files.firstOrNull { it.type == DownloadFileType.ITEM && it.status == DownloadStatus.DOWNLOADED }
            ?: return null
        val mediaFile = media.localPath?.let(storage::resolve) ?: return null
        if (!mediaFile.isFile || mediaFile.length() != media.totalBytes) return null
        val subtitles = files
            .filter { it.type == DownloadFileType.SUBTITLE && it.status == DownloadStatus.DOWNLOADED }
            .mapNotNull { file ->
                val index = file.streamIndex ?: return@mapNotNull null
                val local = file.localPath?.let(storage::resolve)?.takeIf(File::isFile) ?: return@mapNotNull null
                index to local
            }
            .toMap()
        return PlayableDownload(download, decision.mediaSource, mediaFile, subtitles, imageFile(downloadFiles))
    }

    fun imageFile(downloadFiles: DownloadFiles): File? = downloadFiles.files
        .firstOrNull { it.type == DownloadFileType.IMAGE_PRIMARY && it.status == DownloadStatus.DOWNLOADED }
        ?.localPath
        ?.let(storage::resolve)
        ?.takeIf(File::isFile)

    /** Cheap structural re-check of a completed media file, used before reporting it as playable. */
    fun isIntact(playable: PlayableDownload): Boolean =
        MediaValidator.validate(playable.mediaFile, DownloadFileType.ITEM, playable.mediaFile.length()) ==
            MediaValidator.Result.Valid
}
