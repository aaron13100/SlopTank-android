package org.jellyfin.mobile.downloads

import android.net.Uri
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.DownloadFileDao
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.core.DownloadErrorReason
import org.jellyfin.mobile.downloads.core.DownloadRecords
import org.jellyfin.mobile.downloads.core.FileRecord
import org.jellyfin.mobile.downloads.core.PlannedFile

/** [DownloadRecords] backed by Room; every method is a single statement or a Room transaction. */
class RoomDownloadRecords(
    private val downloadDao: DownloadDao,
    private val fileDao: DownloadFileDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : DownloadRecords {
    override suspend fun downloadStatus(downloadId: Long): DownloadStatus? =
        downloadDao.getStatus(downloadId)?.let(DownloadStatus::valueOf)

    override suspend fun transitionStatus(
        downloadId: Long,
        from: DownloadStatus,
        to: DownloadStatus,
        reason: DownloadErrorReason?,
    ): Boolean = downloadDao.transitionStatus(downloadId, from.name, to.name, reason?.name, clock()) == 1

    override suspend fun fileRecord(downloadId: Long, planned: PlannedFile): FileRecord {
        val existing = fileDao.getFile(downloadId, planned.type.name, planned.streamIndex)
        val entity = existing ?: DownloadFileEntity(
            downloadId = downloadId,
            type = planned.type,
            size = 0,
            fileName = planned.fileName,
            uri = Uri.EMPTY,
            streamIndex = planned.streamIndex,
        ).let { it.copy(id = fileDao.insertFile(it)) }
        return entity.toRecord()
    }

    override suspend fun startFile(fileId: Long, total: Long, validator: String?) =
        fileDao.startFile(fileId, total, validator)

    override suspend fun updateFileProgress(fileId: Long, bytes: Long) = fileDao.updateFileProgress(fileId, bytes)

    override suspend fun resetFile(fileId: Long) = fileDao.resetFile(fileId)

    override suspend fun completeFile(fileId: Long, size: Long, relativePath: String) =
        fileDao.completeFile(fileId, size, relativePath)

    override suspend fun completeDownload(downloadId: Long): Boolean =
        downloadDao.completeDownload(downloadId, clock()) == 1

    private fun DownloadFileEntity.toRecord() = FileRecord(
        id = id,
        type = type,
        streamIndex = streamIndex,
        fileName = fileName,
        status = status,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        validator = validator,
    )
}
