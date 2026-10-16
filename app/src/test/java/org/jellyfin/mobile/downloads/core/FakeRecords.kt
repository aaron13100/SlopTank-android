package org.jellyfin.mobile.downloads.core

import org.jellyfin.mobile.downloads.DownloadStatus

/**
 * In-memory stand-in for the Room-backed record store (the database is the platform boundary).
 * The engine calls it sequentially, so plain maps are enough.
 */
class FakeRecords : DownloadRecords {
    val statuses = mutableMapOf<Long, DownloadStatus>()
    val reasons = mutableMapOf<Long, DownloadErrorReason?>()
    val files = mutableMapOf<Long, FileRecord>()
    val paths = mutableMapOf<Long, String>()
    private val owner = mutableMapOf<Long, Long>()
    private var nextId = 1L

    /** Invoked on every progress update, e.g. to pause a download mid-transfer. */
    var onProgress: (FakeRecords) -> Unit = {}

    fun add(downloadId: Long, status: DownloadStatus = DownloadStatus.QUEUED) {
        statuses[downloadId] = status
    }

    override suspend fun downloadStatus(downloadId: Long) = statuses[downloadId]

    override suspend fun transitionStatus(
        downloadId: Long,
        from: DownloadStatus,
        to: DownloadStatus,
        reason: DownloadErrorReason?,
    ): Boolean {
        if (statuses[downloadId] != from) return false
        statuses[downloadId] = to
        reasons[downloadId] = reason
        return true
    }

    override suspend fun fileRecord(downloadId: Long, planned: PlannedFile): FileRecord {
        val existing = files.values.firstOrNull {
            owner[it.id] == downloadId && it.type == planned.type && it.streamIndex == planned.streamIndex
        }
        if (existing != null) return existing
        val record = FileRecord(nextId++, planned.type, planned.streamIndex, planned.fileName, DownloadStatus.QUEUED, 0, -1, null)
        files[record.id] = record
        owner[record.id] = downloadId
        return record
    }

    override suspend fun startFile(fileId: Long, total: Long, validator: String?) {
        files[fileId] = files.getValue(fileId).copy(status = DownloadStatus.DOWNLOADING, totalBytes = total, validator = validator)
    }

    override suspend fun updateFileProgress(fileId: Long, bytes: Long) {
        files[fileId] = files.getValue(fileId).copy(bytesDownloaded = bytes)
        onProgress(this)
    }

    override suspend fun resetFile(fileId: Long) {
        files[fileId] = files.getValue(fileId).copy(status = DownloadStatus.QUEUED, bytesDownloaded = 0, totalBytes = -1, validator = null)
    }

    override suspend fun completeFile(fileId: Long, size: Long, relativePath: String) {
        files[fileId] = files.getValue(fileId).copy(status = DownloadStatus.DOWNLOADED, bytesDownloaded = size, totalBytes = size)
        paths[fileId] = relativePath
    }

    override suspend fun completeDownload(downloadId: Long): Boolean {
        val all = files.values.filter { owner[it.id] == downloadId }
        if (statuses[downloadId] != DownloadStatus.DOWNLOADING || all.any { it.status != DownloadStatus.DOWNLOADED }) return false
        statuses[downloadId] = DownloadStatus.DOWNLOADED
        reasons[downloadId] = null
        return true
    }
}
