package org.jellyfin.mobile.player

import android.content.Context
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.mobile.downloads.progress.ProgressRecorder
import org.jellyfin.mobile.downloads.progress.ProgressSyncWorker
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.extensions.inWholeTicks
import kotlin.time.Duration.Companion.milliseconds

/**
 * Records playback of downloads into the offline progress journal; never touches the network. A completion is
 * counted at most once per playback of a source.
 */
class LocalProgressTracker(
    private val context: Context,
    private val recorder: ProgressRecorder,
) {
    private var completionRecorded = false

    /** Starts a new playback of a source. */
    fun reset() {
        completionRecorded = false
    }

    fun startPeriodic(scope: CoroutineScope, current: () -> Pair<JellyfinMediaSource?, Player?>): Job =
        scope.launch {
            while (true) {
                delay(Constants.PLAYER_TIME_UPDATE_RATE)
                val (source, player) = current()
                record(source, player, final = false)
            }
        }

    /** Records the position; a final record may count a completion and schedules a sync attempt. */
    fun record(source: JellyfinMediaSource?, player: Player?, final: Boolean) {
        val mediaSource = source as? LocalJellyfinMediaSource ?: return
        if (player == null) return
        val downloadId = mediaSource.playSessionId.toLongOrNull() ?: return
        val runtimeTicks = mediaSource.runTime.inWholeTicks
        val positionTicks = player.currentPosition.milliseconds.inWholeTicks
        val ended = player.playbackState == Player.STATE_ENDED
        val completed = final && !completionRecorded && ProgressRecorder.isWatched(positionTicks, runtimeTicks, ended)
        if (completed) completionRecorded = true
        val resumeTicks = ProgressRecorder.resumeTicks(positionTicks, runtimeTicks)
        // The caller's scope may already be cancelled when playback stops, so record on an independent scope
        CoroutineScope(Dispatchers.IO).launch {
            recorder.record(downloadId, resumeTicks, completed)
            if (final) ProgressSyncWorker.schedule(context)
        }
    }
}
