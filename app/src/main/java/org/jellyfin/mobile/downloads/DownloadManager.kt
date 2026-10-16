package org.jellyfin.mobile.downloads

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.core.DownloadErrorReason
import org.jellyfin.mobile.downloads.core.OfflineStorage
import org.jellyfin.mobile.downloads.core.PortableMediaPolicy
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemFields
import java.util.UUID

/**
 * User-facing download operations. Every operation is scoped to one account (local server id and local
 * user id); removal deletes only the app's own offline copy, never anything on the server.
 */
class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val appPreferences: AppPreferences,
    private val storage: OfflineStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Queues [itemIds] for the account using [api]. Items that are not portable are recorded as such. */
    suspend fun enqueueItems(api: ApiClient, serverId: Long, userId: Long, itemIds: Collection<UUID>) =
        withContext(Dispatchers.IO) {
            for (chunk in itemIds.distinct().chunked(ITEMS_BATCH)) {
                val existing = downloadDao.getDownloadsByItemIds(serverId, userId, chunk).associateBy { it.itemId }
                val response by api.itemsApi.getItems(ids = chunk, fields = DOWNLOAD_FIELDS)
                for (item in response.items) {
                    val portable = PortableMediaPolicy.decide(item) is PortableMediaPolicy.Decision.Portable
                    val current = existing[item.id]
                    when {
                        current == null -> downloadDao.insert(
                            DownloadEntity(
                                serverId = serverId,
                                userId = userId,
                                itemId = item.id,
                                path = item.id.toString(),
                                item = item,
                                status = if (portable) DownloadStatus.QUEUED else DownloadStatus.ERROR,
                                errorReason = if (portable) null else DownloadErrorReason.NOT_PORTABLE,
                            ),
                        )
                        // Asking again for a running or finished download changes nothing (idempotent).
                        current.status in SETTLED_OR_RUNNING -> Unit
                        else -> downloadDao.requeueWithItem(current.id, item, clock())
                    }
                }
            }
            DownloadWorker.start(context, appPreferences)
        }

    /** Pauses a queued or running download; its partial data is kept. */
    suspend fun pause(id: Long) = withContext(Dispatchers.IO) {
        PAUSABLE.any { from ->
            downloadDao.transitionStatus(id, from.name, DownloadStatus.PAUSED.name, null, clock()) == 1
        }
    }

    /** Resumes a paused download or retries a failed one from its partial data. */
    suspend fun resume(id: Long) = withContext(Dispatchers.IO) {
        val changed = RESUMABLE.any { from ->
            downloadDao.transitionStatus(id, from.name, DownloadStatus.QUEUED.name, null, clock()) == 1
        }
        if (changed) DownloadWorker.start(context, appPreferences)
        changed
    }

    /** Removes the download and its local files. A running transfer notices the removal and stops. */
    suspend fun remove(download: DownloadEntity) = withContext(Dispatchers.IO) {
        downloadDao.delete(download.id)
        storage.deleteTree(storage.itemDir(download.serverId, download.userId, download.itemId))
    }

    /** Removes every download of one account. */
    suspend fun removeAll(serverId: Long, userId: Long) = withContext(Dispatchers.IO) {
        downloadDao.getDownloadsForAccount(serverId, userId).forEach { downloadDao.delete(it.id) }
        storage.deleteTree(storage.accountDir(serverId, userId))
    }

    /** Bytes the account's offline copies use on this device, including partial files. */
    fun usedBytes(serverId: Long, userId: Long): Long = storage.usedBytes(storage.accountDir(serverId, userId))

    fun availableBytes(): Long = storage.availableBytes()

    private companion object {
        /** How many items are requested from the server at once. */
        const val ITEMS_BATCH = 25

        val SETTLED_OR_RUNNING = setOf(DownloadStatus.DOWNLOADING, DownloadStatus.DOWNLOADED)

        val PAUSABLE = listOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED)

        val RESUMABLE = listOf(DownloadStatus.PAUSED, DownloadStatus.ERROR, DownloadStatus.CANCELLED)

        val DOWNLOAD_FIELDS = setOf(
            ItemFields.MEDIA_SOURCES,
            ItemFields.MEDIA_STREAMS,
            ItemFields.PATH,
            ItemFields.CHAPTERS,
            ItemFields.OVERVIEW,
            ItemFields.GENRES,
        )
    }
}
