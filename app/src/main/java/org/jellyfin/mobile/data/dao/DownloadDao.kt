package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.data.entity.DownloadKey
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Every catalogue read is scoped by local server id and local user id, so one account can never see
 * another account's downloads. Only the download worker reads across accounts, and only by row id.
 */
@Dao
interface DownloadDao {
    @Transaction
    @Query("SELECT * FROM download WHERE server_id = :serverId AND user_id = :userId ORDER BY created_at DESC")
    fun getDownloadsWithFiles(serverId: Long, userId: Long): Flow<List<DownloadFiles>>

    @Transaction
    @Query("SELECT * FROM download WHERE status = 'QUEUED' ORDER BY created_at ASC")
    suspend fun getQueuedDownloads(): List<DownloadFiles>

    /** A worker that was killed leaves rows in DOWNLOADING; only one worker exists, so requeue them. */
    @Query("UPDATE download SET status = 'QUEUED' WHERE status = 'DOWNLOADING'")
    suspend fun requeueInterrupted(): Int

    @Query("SELECT * FROM download WHERE server_id = :serverId AND user_id = :userId AND item_id IN (:itemIds)")
    suspend fun getDownloadsByItemIds(serverId: Long, userId: Long, itemIds: Collection<UUID>): List<DownloadEntity>

    @Transaction
    @Query("SELECT * FROM download WHERE server_id = :serverId AND user_id = :userId AND item_id = :itemId")
    suspend fun getDownloadByItemId(serverId: Long, userId: Long, itemId: UUID): DownloadFiles?

    @Transaction
    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun getDownloadWithFiles(id: Long): DownloadFiles?

    /** Identity of every download, used only to find orphaned files. */
    @Query("SELECT server_id, user_id, item_id FROM download")
    suspend fun getAllDownloadKeys(): List<DownloadKey>

    @Query("SELECT * FROM download WHERE server_id = :serverId AND user_id = :userId")
    suspend fun getDownloadsForAccount(serverId: Long, userId: Long): List<DownloadEntity>

    @Query("SELECT status FROM download WHERE id = :id")
    suspend fun getStatus(id: Long): String?

    @Query(
        "UPDATE download SET status = :to, error_reason = :reason, modified_at = :now " +
            "WHERE id = :id AND status = :from",
    )
    suspend fun transitionStatus(id: Long, from: String, to: String, reason: String?, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DownloadEntity): Long

    /** Refreshes the cached item metadata of an existing download and queues it again. */
    @Query(
        "UPDATE download SET item = :item, status = 'QUEUED', error_reason = NULL, modified_at = :now WHERE id = :id"
    )
    suspend fun requeueWithItem(id: Long, item: BaseItemDto, now: Long): Int

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: Long)

    /** Marks a download complete only if it is still running and every one of its files is complete. */
    @Query(
        "UPDATE download SET status = 'DOWNLOADED', error_reason = NULL, modified_at = :now " +
            "WHERE id = :id AND status = 'DOWNLOADING' AND NOT EXISTS " +
            "(SELECT 1 FROM download_file WHERE download_id = :id AND status != 'DOWNLOADED')",
    )
    suspend fun completeDownload(id: Long, now: Long): Int
}
