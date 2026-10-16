package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.core.DownloadEngine
import org.jellyfin.mobile.downloads.core.DownloadErrorReason
import org.jellyfin.mobile.downloads.core.EngineOutcome
import org.jellyfin.mobile.downloads.core.OfflineStorage
import timber.log.Timber

/**
 * Processes queued downloads one at a time with the account each download belongs to. Each download uses
 * its own account's credentials, never the currently selected account's.
 */
class DownloadQueue(
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val engine: DownloadEngine,
    private val planner: DownloadPlanner,
    private val storage: OfflineStorage,
    private val downloadNotificationManager: DownloadNotificationManager,
) {
    /**
     * Runs every queued download once. Returns true if a transient failure means the worker should retry.
     */
    suspend fun processAll(): Boolean {
        // Only one worker runs at a time, so anything still marked as downloading was interrupted.
        downloadDao.requeueInterrupted()
        removeOrphanedFiles()

        val attempted = mutableSetOf<Long>()
        var retry = false
        while (true) {
            val next = downloadDao.getQueuedDownloads().firstOrNull { it.download.id !in attempted } ?: break
            attempted += next.download.id
            if (process(next.download) == EngineOutcome.RETRY_LATER) retry = true
        }
        return retry
    }

    private suspend fun process(download: DownloadEntity): EngineOutcome {
        val api = apiClientController.getApiClientOrNull(download.serverId, download.userId)
        if (api == null) {
            markFailed(download, DownloadErrorReason.AUTH_EXPIRED)
            return EngineOutcome.FAILED
        }
        return when (val plan = planner.plan(download, api)) {
            is DownloadPlanner.Plan.NotPortable -> {
                markFailed(download, DownloadErrorReason.NOT_PORTABLE)
                EngineOutcome.FAILED
            }
            is DownloadPlanner.Plan.Ready -> {
                val notification = downloadNotificationManager.downloadFile(
                    download.id,
                    download.getDisplayName(downloadNotificationManager.context).orEmpty(),
                )
                val outcome = engine.run(plan.job) { downloaded, total -> notification.onProgress(downloaded, total) }
                if (outcome == EngineOutcome.COMPLETED) notification.onEnd()
                engine.lastFailure?.let { Timber.w(it, "Download %d ended as %s", download.id, outcome) }
                outcome
            }
        }
    }

    private suspend fun markFailed(download: DownloadEntity, reason: DownloadErrorReason) {
        downloadDao.transitionStatus(
            download.id,
            DownloadStatus.QUEUED.name,
            DownloadStatus.ERROR.name,
            reason.name,
            System.currentTimeMillis(),
        )
    }

    /** Deletes item folders of downloads that were removed while a transfer was still writing. */
    private suspend fun removeOrphanedFiles() {
        val known = downloadDao.getAllDownloadKeys().map { storage.itemDir(it.serverId, it.userId, it.itemId) }.toSet()
        storage.root.listFiles().orEmpty().forEach { server ->
            server.listFiles().orEmpty().forEach { account ->
                account.listFiles().orEmpty().filter { it !in known }.forEach(storage::deleteTree)
            }
        }
    }
}
