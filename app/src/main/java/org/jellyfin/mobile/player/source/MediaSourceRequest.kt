package org.jellyfin.mobile.player.source

import org.jellyfin.sdk.model.api.DeviceProfile
import java.util.UUID
import kotlin.time.Duration

/**
 * Parameters used by [MediaSourceResolver.resolveMediaSource] to query playback info for an item.
 */
data class MediaSourceRequest(
    val itemId: UUID,
    val mediaSourceId: String? = null,
    val deviceProfile: DeviceProfile? = null,
    val maxStreamingBitrate: Int? = null,
    val startTime: Duration? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val autoOpenLiveStream: Boolean = true,
    val enableDirectPlay: Boolean? = null,
    val enableDirectStream: Boolean? = null,
)
