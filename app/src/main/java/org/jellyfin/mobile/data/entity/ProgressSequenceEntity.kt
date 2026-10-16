package org.jellyfin.mobile.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** The next offline operation sequence number of one account (server and user). */
@Entity(tableName = "progress_sequence", primaryKeys = ["server_id", "user_id"])
data class ProgressSequenceEntity(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "last_sequence") val lastSequence: Long,
)
