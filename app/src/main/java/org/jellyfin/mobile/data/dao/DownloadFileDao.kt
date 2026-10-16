package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.jellyfin.mobile.data.entity.DownloadFileEntity

/** The files (media, artwork, subtitles) of downloads. Each method is a single atomic statement. */
@Dao
interface DownloadFileDao {
    @Query("SELECT * FROM download_file WHERE download_id = :downloadId")
    suspend fun getFiles(downloadId: Long): List<DownloadFileEntity>

    @Query(
        "SELECT * FROM download_file WHERE download_id = :downloadId AND type = :type " +
            "AND ((stream_index IS NULL AND :streamIndex IS NULL) OR stream_index = :streamIndex) LIMIT 1",
    )
    suspend fun getFile(downloadId: Long, type: String, streamIndex: Int?): DownloadFileEntity?

    @Query("SELECT * FROM download_file WHERE id = :id")
    suspend fun getFileById(id: Long): DownloadFileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFile(entity: DownloadFileEntity): Long

    @Query(
        "UPDATE download_file SET status = 'DOWNLOADING', total_bytes = :total, validator = :validator, " +
            "local_path = NULL WHERE id = :id",
    )
    suspend fun startFile(id: Long, total: Long, validator: String?)

    @Query("UPDATE download_file SET bytes_downloaded = :bytes WHERE id = :id")
    suspend fun updateFileProgress(id: Long, bytes: Long)

    @Query(
        "UPDATE download_file SET status = 'QUEUED', bytes_downloaded = 0, total_bytes = -1, size = 0, " +
            "validator = NULL, local_path = NULL WHERE id = :id",
    )
    suspend fun resetFile(id: Long)

    /** Forgets every object of a download so it is fetched again; the published files are replaced on success. */
    @Query(
        "UPDATE download_file SET status = 'QUEUED', bytes_downloaded = 0, total_bytes = -1, size = 0, " +
            "validator = NULL, local_path = NULL WHERE download_id = :downloadId",
    )
    suspend fun resetFilesOfDownload(downloadId: Long)

    @Query(
        "UPDATE download_file SET status = 'DOWNLOADED', bytes_downloaded = :size, total_bytes = :size, " +
            "size = :size, local_path = :localPath WHERE id = :id",
    )
    suspend fun completeFile(id: Long, size: Long, localPath: String)
}
