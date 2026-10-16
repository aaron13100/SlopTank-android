package org.jellyfin.mobile.webapp

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.utils.Constants

// Indices of the notification actions shown in the compact view when the system provides native seeking
// (actions: previous, play/pause, next, stop)
private const val NATIVE_SEEK_PREVIOUS_ACTION_INDEX = 0
private const val NATIVE_SEEK_PLAY_PAUSE_ACTION_INDEX = 1
private const val NATIVE_SEEK_NEXT_ACTION_INDEX = 2

// Indices of the notification actions shown in the compact view without native seeking
// (actions: previous, rewind, play/pause, fast forward, next, stop)
private const val LEGACY_PREVIOUS_ACTION_INDEX = 0
private const val LEGACY_PLAY_PAUSE_ACTION_INDEX = 2
private const val LEGACY_NEXT_ACTION_INDEX = 4

private const val DELETE_INTENT_REQUEST_CODE = 1

/**
 * Shows the previous, play/pause and next actions in the compact notification view.
 */
internal fun Notification.MediaStyle.setRemotePlayerCompactActions(supportsNativeSeek: Boolean) {
    if (supportsNativeSeek) {
        setShowActionsInCompactView(
            NATIVE_SEEK_PREVIOUS_ACTION_INDEX,
            NATIVE_SEEK_PLAY_PAUSE_ACTION_INDEX,
            NATIVE_SEEK_NEXT_ACTION_INDEX,
        )
    } else {
        setShowActionsInCompactView(
            LEGACY_PREVIOUS_ACTION_INDEX,
            LEGACY_PLAY_PAUSE_ACTION_INDEX,
            LEGACY_NEXT_ACTION_INDEX,
        )
    }
}

internal fun Context.createRemotePlayerDeleteIntent(): PendingIntent {
    val intent = Intent(applicationContext, RemotePlayerService::class.java).apply {
        action = Constants.ACTION_STOP
    }
    return PendingIntent.getService(
        applicationContext,
        DELETE_INTENT_REQUEST_CODE,
        intent,
        Constants.PENDING_INTENT_FLAGS,
    )
}

internal fun Context.createRemotePlayerContentIntent(): PendingIntent {
    val intent = Intent(this, MainActivity::class.java).apply {
        action = Constants.ACTION_SHOW_PLAYER
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
        this,
        Constants.REMOTE_PLAYER_CONTENT_INTENT_REQUEST_CODE,
        intent,
        Constants.PENDING_INTENT_FLAGS,
    )
}
