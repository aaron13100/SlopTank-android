package org.jellyfin.mobile.downloads

import android.content.Context
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.DownloadFileDao
import org.jellyfin.mobile.downloads.core.CatalogDecision
import org.jellyfin.mobile.downloads.core.DownloadErrorReason
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

/**
 * While online, compares an account's completed downloads with the server's current items: an item deleted on
 * the server stays playable offline and is marked as removed from the server; an item whose media was replaced
 * is downloaded again. Never deletes server or library media.
 */
class CatalogRefresher(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val fileDao: DownloadFileDao,
    private val appPreferences: AppPreferences,
) {
    suspend fun refresh(serverId: Long, userId: Long, api: ApiClient) {
        val completed = downloadDao.getDownloadsForAccount(
            serverId,
            userId
        ).filter { it.status == DownloadStatus.DOWNLOADED }
        if (completed.isEmpty()) return
        val fresh = try {
            completed.map { it.itemId }.chunked(BATCH).flatMap { ids ->
                api.itemsApi.getItems(ids = ids, fields = FIELDS).content.items
            }.associateBy { it.id }
        } catch (e: ApiClientException) {
            Timber.w(e, "Catalog refresh skipped for account %d/%d", serverId, userId)
            return
        }
        var requeued = false
        for (download in completed) {
            val now = System.currentTimeMillis()
            when (val decision = CatalogDecision.decide(download.item, fresh[download.itemId])) {
                CatalogDecision.Gone -> downloadDao.transitionStatus(
                    download.id,
                    DOWNLOADED,
                    DOWNLOADED,
                    DownloadErrorReason.SERVER_ITEM_GONE.name,
                    now,
                )
                CatalogDecision.Unchanged -> if (download.errorReason != null) {
                    downloadDao.transitionStatus(download.id, DOWNLOADED, DOWNLOADED, null, now)
                }
                is CatalogDecision.Replaced -> {
                    fileDao.resetFilesOfDownload(download.id)
                    downloadDao.requeueWithItem(download.id, decision.fresh, now)
                    requeued = true
                }
            }
        }
        if (requeued) DownloadWorker.start(context, appPreferences)
    }

    private companion object {
        const val BATCH = 25
        val DOWNLOADED = DownloadStatus.DOWNLOADED.name
        val FIELDS = setOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS, ItemFields.PATH, ItemFields.CHAPTERS)
    }
}
