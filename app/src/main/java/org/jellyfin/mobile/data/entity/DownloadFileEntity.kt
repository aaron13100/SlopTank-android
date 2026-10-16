package org.jellyfin.mobile.data.entity

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus

@Entity(
    tableName = "download_file",
    indices = [Index(value = ["download_id"])],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["download_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadFileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "download_id") val downloadId: Long,
    @ColumnInfo(name = "type") val type: DownloadFileType,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "uri") val uri: Uri,
    @ColumnInfo(name = "status") val status: DownloadStatus = DownloadStatus.QUEUED,
    /** Bytes received so far for the current object. */
    @ColumnInfo(name = "bytes_downloaded", defaultValue = "0") val bytesDownloaded: Long = 0L,
    /** Total size announced by the server for the current object, -1 if not known yet. */
    @ColumnInfo(name = "total_bytes", defaultValue = "-1") val totalBytes: Long = -1L,
    /** Strong ETag or Last-Modified of the object the partial file belongs to. */
    @ColumnInfo(name = "validator") val validator: String? = null,
    /** Media stream index, for subtitle files. */
    @ColumnInfo(name = "stream_index") val streamIndex: Int? = null,
    /** Path of the published object relative to the offline store, null until complete. */
    @ColumnInfo(name = "local_path") val localPath: String? = null,
)
