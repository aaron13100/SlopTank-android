package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.jellyfin.mobile.data.ProgressJournalSchema
import org.jellyfin.mobile.data.entity.OfflineProgressEntity
import org.jellyfin.mobile.data.entity.ProgressSequenceEntity

@Dao
interface ProgressDao {
    @Query("SELECT * FROM offline_progress WHERE server_id = :serverId AND user_id = :userId ORDER BY download_id")
    suspend fun getEntries(serverId: Long, userId: Long): List<OfflineProgressEntity>

    @Query("SELECT * FROM offline_progress WHERE download_id = :downloadId")
    suspend fun getEntry(downloadId: Long): OfflineProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfflineProgressEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: OfflineProgressEntity): Long

    /** Returns 1 if the entry was deleted: only when its download is gone and the entry is settled. */
    @Query(ProgressJournalSchema.PURGE_IF_DETACHED)
    suspend fun purgeIfDetached(downloadId: Long): Int

    @Query("SELECT DISTINCT server_id, user_id FROM offline_progress")
    suspend fun getAccounts(): List<AccountKey>

    @Query("SELECT last_sequence FROM progress_sequence WHERE server_id = :serverId AND user_id = :userId")
    suspend fun getLastSequence(serverId: Long, userId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setLastSequence(entity: ProgressSequenceEntity)

    data class AccountKey(
        @androidx.room.ColumnInfo(name = "server_id") val serverId: Long,
        @androidx.room.ColumnInfo(name = "user_id") val userId: Long,
    )
}
