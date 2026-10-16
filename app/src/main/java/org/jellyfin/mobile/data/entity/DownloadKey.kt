package org.jellyfin.mobile.data.entity

import androidx.room.ColumnInfo
import java.util.UUID

/** The account-scoped identity of a download. */
data class DownloadKey(
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,
)
