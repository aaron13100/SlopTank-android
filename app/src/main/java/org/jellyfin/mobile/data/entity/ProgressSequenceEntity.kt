package org.jellyfin.mobile.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.util.UUID

/**
 * The last offline operation sequence number of one account (server and user), labelled with the server identity
 * and server-side user it was used for, so a re-added account of the same user continues it.
 */
@Entity(tableName = "progress_sequence", primaryKeys = ["server_id", "user_id"])
data class ProgressSequenceEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "last_sequence") val lastSequence: Long,
    @ColumnInfo(name = "server_identity") val serverIdentity: String? = null,
    @ColumnInfo(name = "account_user_id") val accountUserId: UUID? = null,
)
