package org.jellyfin.mobile.downloads.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import java.io.File
import java.io.IOException

/** One object of a download (media file, artwork or an external subtitle) and where to fetch it. */
data class PlannedFile(
    val type: DownloadFileType,
    val fileName: String,
    val url: String,
    val streamIndex: Int? = null,
)

/** The durable state of one [PlannedFile]. */
data class FileRecord(
    val id: Long,
    val type: DownloadFileType,
    val streamIndex: Int?,
    val fileName: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    /** Total size announced by the server when the current partial file was started, -1 if unknown. */
    val totalBytes: Long,
    /** Validator (strong ETag or Last-Modified) of the object the partial file belongs to. */
    val validator: String?,
)

/**
 * Durable download bookkeeping (Room in the app). Every method is one atomic write, so a process kill
 * at any point leaves a consistent record that the next run resumes from.
 */
interface DownloadRecords {
    /** The download's status, or null once it has been removed. */
    suspend fun downloadStatus(downloadId: Long): DownloadStatus?

    /** Changes the status only if it currently is [from]; returns whether it changed. */
    suspend fun transitionStatus(
        downloadId: Long,
        from: DownloadStatus,
        to: DownloadStatus,
        reason: DownloadErrorReason?,
    ): Boolean

    /** Returns the record for [planned], creating it if needed. */
    suspend fun fileRecord(downloadId: Long, planned: PlannedFile): FileRecord

    /** Persists the total size and validator of the object before any of its bytes are written. */
    suspend fun startFile(fileId: Long, total: Long, validator: String?)

    suspend fun updateFileProgress(fileId: Long, bytes: Long)

    /** Forgets a discarded object: status queued, no bytes, no size, no validator. */
    suspend fun resetFile(fileId: Long)

    /** Marks a validated and published object as complete. */
    suspend fun completeFile(fileId: Long, size: Long, relativePath: String)

    /** Marks the whole download as complete; called only after every file is complete. */
    suspend fun completeDownload(downloadId: Long): Boolean
}

data class DownloadJob(
    val downloadId: Long,
    val itemDir: File,
    val headers: Map<String, String>,
    val files: List<PlannedFile>,
)

enum class EngineOutcome {
    /** Every file is validated, published and recorded; the download is playable. */
    COMPLETED,

    /** A transient failure; the download is queued again and should be retried later. */
    RETRY_LATER,

    /** A permanent failure was recorded with its reason. */
    FAILED,

    /** The user paused, cancelled or removed the download while it was running. */
    INTERRUPTED,
}

/**
 * Runs one download to completion. Each object is received into a partial file, validated, and only then
 * renamed to its final name and recorded as complete, so a partial or corrupt object is never playable.
 */
class DownloadEngine(
    private val records: DownloadRecords,
    private val storage: OfflineStorage,
    private val downloader: RangeDownloader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(job: DownloadJob): EngineOutcome {
        val id = job.downloadId
        if (!records.transitionStatus(id, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, null)) {
            // Paused, cancelled, removed or already complete since it was queued.
            return EngineOutcome.INTERRUPTED
        }
        return try {
            storage.prepareDir(job.itemDir)
            for (planned in job.files) {
                if (records.downloadStatus(id) != DownloadStatus.DOWNLOADING) throw DownloadInterruptedException()
                downloadFile(job, planned)
            }
            if (records.completeDownload(id)) EngineOutcome.COMPLETED else EngineOutcome.INTERRUPTED
        } catch (e: DownloadInterruptedException) {
            EngineOutcome.INTERRUPTED
        } catch (e: PermanentDownloadException) {
            records.transitionStatus(id, DownloadStatus.DOWNLOADING, DownloadStatus.ERROR, e.reason)
            EngineOutcome.FAILED
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                records.transitionStatus(id, DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, null)
            }
            throw e
        } catch (e: IOException) {
            records.transitionStatus(id, DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadErrorReason.NETWORK)
            EngineOutcome.RETRY_LATER
        }
    }

    private suspend fun downloadFile(job: DownloadJob, planned: PlannedFile) {
        var record = records.fileRecord(job.downloadId, planned)
        val finalFile = storage.finalFile(job.itemDir, planned.fileName)
        val part = storage.partFile(finalFile)

        if (isPublishedAndValid(record, finalFile)) {
            if (record.status != DownloadStatus.DOWNLOADED) {
                // Killed after the rename but before the record was updated: finish the bookkeeping.
                records.completeFile(record.id, record.totalBytes, storage.relativePath(finalFile))
            }
            return
        }
        if (record.status == DownloadStatus.DOWNLOADED || finalFile.exists()) {
            // A completed object went missing or no longer validates: discard it and start over.
            finalFile.delete()
            records.resetFile(record.id)
            record = record.copy(status = DownloadStatus.QUEUED, bytesDownloaded = 0, totalBytes = -1, validator = null)
        }

        val result = downloader.download(
            RangeDownloader.Request(
                url = planned.url,
                headers = job.headers,
                part = part,
                validator = record.validator,
                knownTotal = record.totalBytes,
            ),
            ProgressListener(job.downloadId, record.id),
        )

        val validation = MediaValidator.validate(part, planned.type, result.total)
        if (validation is MediaValidator.Result.Invalid) {
            part.delete()
            records.resetFile(record.id)
            throw ValidationFailedException("${planned.fileName}: ${validation.reason}")
        }

        storage.publish(part, finalFile)
        records.completeFile(record.id, result.total, storage.relativePath(finalFile))
    }

    private fun isPublishedAndValid(record: FileRecord, finalFile: File): Boolean =
        record.totalBytes > 0 && finalFile.exists() &&
            MediaValidator.validate(finalFile, record.type, record.totalBytes) == MediaValidator.Result.Valid

    private inner class ProgressListener(
        private val downloadId: Long,
        private val fileId: Long,
    ) : RangeDownloader.Listener {
        private var lastPersisted = 0L

        override suspend fun onStart(total: Long, validator: String?) {
            records.startFile(fileId, total, validator)
        }

        override suspend fun onProgress(downloaded: Long, total: Long) {
            val now = clock()
            if (downloaded == total || now - lastPersisted >= PROGRESS_PERSIST_INTERVAL_MS) {
                lastPersisted = now
                records.updateFileProgress(fileId, downloaded)
            }
        }

        override suspend fun shouldContinue(): Boolean =
            records.downloadStatus(downloadId) == DownloadStatus.DOWNLOADING
    }

    private companion object {
        const val PROGRESS_PERSIST_INTERVAL_MS = 500L
    }
}
