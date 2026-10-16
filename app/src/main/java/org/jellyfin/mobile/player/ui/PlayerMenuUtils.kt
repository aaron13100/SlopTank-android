package org.jellyfin.mobile.player.ui

import android.view.Menu
import android.view.MenuItem
import org.jellyfin.sdk.model.api.MediaStream
import java.util.Locale

private const val BITRATE_MEGA_BIT = 1_000_000
private const val BITRATE_KILO_BIT = 1_000

/**
 * Format a [bitrate] in bits per second as human readable string, e.g. "1.5 Mbps".
 */
internal fun formatBitrate(bitrate: Double): String {
    val (value, unit) = when {
        bitrate > BITRATE_MEGA_BIT -> bitrate / BITRATE_MEGA_BIT to " Mbps"
        bitrate > BITRATE_KILO_BIT -> bitrate / BITRATE_KILO_BIT to " kbps"
        else -> bitrate to " bps"
    }

    // Remove unnecessary trailing zeros
    val formatted = "%.2f".format(Locale.getDefault(), value).removeSuffix(".00")
    return formatted + unit
}

/**
 * Replace the items of this menu with one checkable item per media stream in [mediaStreams].
 *
 * @param noneTitle if not null, an additional "none" item with id -1 and this title is added first.
 */
internal fun Menu.buildStreamMenuItems(
    groupId: Int,
    mediaStreams: List<MediaStream>,
    selectedStream: MediaStream?,
    noneTitle: String? = null,
) {
    clear()
    val itemNone = when {
        noneTitle != null -> add(groupId, -1, Menu.NONE, noneTitle)
        else -> null
    }
    var selectedItem: MenuItem? = itemNone
    val menuItems = mediaStreams.map { mediaStream ->
        val title = mediaStream.displayTitle ?: "${mediaStream.language} (${mediaStream.codec})"
        add(groupId, mediaStream.index, Menu.NONE, title).also { item ->
            if (mediaStream === selectedStream) {
                selectedItem = item
            }
        }
    }
    setGroupCheckable(groupId, true, true)
    // Check selected item or first item if possible
    (selectedItem ?: menuItems.firstOrNull())?.isChecked = true
}
