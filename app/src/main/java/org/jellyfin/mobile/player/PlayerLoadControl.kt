package org.jellyfin.mobile.player

import androidx.media3.exoplayer.DefaultLoadControl
import org.jellyfin.mobile.utils.Constants

private const val LARGE_MIN_BUFFER_MS = 50_000
private const val LARGE_MAX_BUFFER_MS = 120_000
private const val LARGE_BUFFER_FOR_PLAYBACK_MS = 2_500
private const val LARGE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

private const val EXTRA_LARGE_MIN_BUFFER_MS = 80_000
private const val EXTRA_LARGE_MAX_BUFFER_MS = 240_000
private const val EXTRA_LARGE_BUFFER_FOR_PLAYBACK_MS = 5_000
private const val EXTRA_LARGE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000

/**
 * Build the [DefaultLoadControl] matching the network buffer preference value [networkBuffer].
 */
internal fun buildLoadControl(networkBuffer: String): DefaultLoadControl = when (networkBuffer) {
    Constants.NETWORK_BUFFER_LARGE -> DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            LARGE_MIN_BUFFER_MS,
            LARGE_MAX_BUFFER_MS,
            LARGE_BUFFER_FOR_PLAYBACK_MS,
            LARGE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()
    Constants.NETWORK_BUFFER_EXTRA_LARGE -> DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            EXTRA_LARGE_MIN_BUFFER_MS,
            EXTRA_LARGE_MAX_BUFFER_MS,
            EXTRA_LARGE_BUFFER_FOR_PLAYBACK_MS,
            EXTRA_LARGE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()
    else -> DefaultLoadControl()
}
