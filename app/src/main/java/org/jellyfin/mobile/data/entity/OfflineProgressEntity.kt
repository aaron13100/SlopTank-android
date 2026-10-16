package org.jellyfin.mobile.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * The offline progress journal entry of one download (see downloads/progress). It deliberately has no foreign
 * key to the download: removing the download keeps an entry that is not settled until the server acknowledges
 * it or the conflict is resolved, and a trigger refuses to delete it before that (see [org.jellyfin.mobile.data.ProgressJournalSchema]).
 */
@Entity(
    tableName = "offline_progress",
    indices = [Index(value = ["server_id", "user_id"])],
)
data class OfflineProgressEntity(
    @PrimaryKey @ColumnInfo(name = "download_id") val downloadId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "server_identity") val serverIdentity: String?,
    @ColumnInfo(name = "base_revision") val baseRevision: Long?,
    @ColumnInfo(name = "base_position_ticks") val basePositionTicks: Long?,
    @ColumnInfo(name = "base_played") val basePlayed: Boolean?,
    @ColumnInfo(name = "base_play_count") val basePlayCount: Int?,
    @ColumnInfo(name = "local_position_ticks") val localPositionTicks: Long,
    @ColumnInfo(name = "local_played") val localPlayed: Boolean,
    @ColumnInfo(name = "local_completions") val localCompletions: Int,
    @ColumnInfo(name = "local_last_played") val localLastPlayed: String?,
    @ColumnInfo(name = "dirty") val dirty: Boolean,
    @ColumnInfo(name = "op_id") val operationId: UUID?,
    @ColumnInfo(name = "op_sequence") val operationSequence: Long?,
    @ColumnInfo(name = "op_base_revision") val operationBaseRevision: Long?,
    @ColumnInfo(name = "op_position_ticks") val operationPositionTicks: Long?,
    @ColumnInfo(name = "op_played") val operationPlayed: Boolean?,
    @ColumnInfo(name = "op_play_count_increment") val operationPlayCountIncrement: Int?,
    @ColumnInfo(name = "op_last_played") val operationLastPlayed: String?,
    @ColumnInfo(name = "state") val state: String,
)
