package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.jellyfin.mobile.data.ProgressJournalSchema
import org.jellyfin.mobile.data.entity.OfflineProgressEntity
import org.jellyfin.mobile.data.entity.ProgressSequenceEntity
import java.util.UUID

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

    /** The last sequence of the account, or of any account of the same server-side user and server. */
    @Query(ProgressJournalSchema.LAST_SEQUENCE)
    suspend fun getLastSequence(serverId: Long, userId: Long, serverIdentity: String?, accountUserId: UUID?): Long?

    @Query(ProgressJournalSchema.FILL_ACCOUNT_USER)
    suspend fun fillAccountUser(serverId: Long, userId: Long, accountUserId: UUID): Int

    /** Moves entries kept from a removed or signed-out account of the same user and server; see the SQL. */
    @Query(ProgressJournalSchema.ADOPT_ENTRIES)
    suspend fun adoptEntries(serverId: Long, userId: Long, accountUserId: UUID, serverIdentity: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setLastSequence(entity: ProgressSequenceEntity)

    data class AccountKey(
        @androidx.room.ColumnInfo(name = "server_id") val serverId: Long,
        @androidx.room.ColumnInfo(name = "user_id") val userId: Long,
    )
}
