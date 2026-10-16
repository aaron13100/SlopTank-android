package org.jellyfin.mobile.acceptance

import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.acceptance.Phase6Step1DownloadOnlineTest.Companion.KEY_DOWNLOAD_ID
import org.jellyfin.mobile.acceptance.Phase6Step1DownloadOnlineTest.Companion.KEY_ITEM_NAME
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.downloads.progress.SyncState
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.sdk.model.api.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 6, step 2 (airplane mode on, app force-stopped before this step): open Downloads from the app's
 * offline entry point, play the download, seek, switch audio and subtitles and play to the end. The
 * completion must land in the offline journal and nothing may reach the server.
 */
@RunWith(AndroidJUnit4::class)
class Phase6Step2OfflinePlaybackTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val database = Phase6.koin<JellyfinDatabase>()

    @Test
    fun browsesAndPlaysTheDownloadOffline() = runBlocking {
        val state = Phase6.readState()
        val downloadId = state.getValue(KEY_DOWNLOAD_ID).toLong()
        assertFalse("airplane mode must be on", Phase6.hasNetwork())
        assertFalse("the server must be unreachable", Phase6.serverReachable())
        assertEquals(0, database.progressDao.getEntry(downloadId)!!.localCompletions)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Offline the web client cannot load; the loading screen and the server screen both offer Downloads.
            val label = Phase6.context.getString(R.string.view_downloads)
            device.find(By.text(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE)), ENTRY_TIMEOUT)
                .click()
            device.find(By.text(state.getValue(KEY_ITEM_NAME)), UI_TIMEOUT).click()

            val viewModel = Phase6.awaitValue(PLAYER_TIMEOUT, "the player to be ready") {
                scenario.playerViewModel()?.takeIf { onMain { it.playerOrNull?.playbackState == Player.STATE_READY } }
            }
            val source = viewModel.mediaSourceOrNull
            assertTrue("the download plays from local files", source is LocalJellyfinMediaSource)
            val player = requireNotNull(viewModel.playerOrNull)

            seekToMiddle(player)
            switchAudio(viewModel, player, source!!.audioStreams)
            switchSubtitles(viewModel, player, source.subtitleStreams)

            onMain {
                player.seekTo(player.duration - FINISH_LEAD_MS)
                player.play()
            }
            val entry = Phase6.awaitValue(FINISH_TIMEOUT, "the offline completion in the journal") {
                database.progressDao.getEntry(downloadId)?.takeIf { it.localCompletions > 0 }
            }
            assertEquals("one playback to the end is one completion", 1, entry.localCompletions)
            assertTrue(entry.localPlayed)
            assertEquals(SyncState.PENDING.name, entry.state)
        }
        assertFalse("still offline at the end of playback", Phase6.serverReachable())
    }

    private suspend fun seekToMiddle(player: ExoPlayer) {
        val target = onMain { (player.duration / 2).also(player::seekTo) }
        Phase6.awaitValue(TRACK_TIMEOUT, "the seek to ${target}ms") {
            onMain { player.takeIf { it.playbackState == Player.STATE_READY && it.currentPosition >= target } }
        }
    }

    private suspend fun switchAudio(viewModel: PlayerViewModel, player: ExoPlayer, streams: List<MediaStream>) {
        assertTrue("the test item has two audio tracks", streams.size >= 2)
        val before = onMain { player.selectedTrack(C.TRACK_TYPE_AUDIO) }
        val other = streams.first { it.index != viewModel.mediaSourceOrNull!!.selectedAudioStreamIndex }
        assertTrue(onMain { viewModel.trackSelectionHelper.selectAudioTrack(other.index) })
        Phase6.awaitValue(TRACK_TIMEOUT, "the audio track to change") {
            onMain { player.selectedTrack(C.TRACK_TYPE_AUDIO)?.takeIf { it != before } }
        }
    }

    private suspend fun switchSubtitles(viewModel: PlayerViewModel, player: ExoPlayer, streams: List<MediaStream>) {
        assertTrue("the test item has two downloaded subtitles", streams.size >= 2)
        var previous: String? = onMain { player.selectedTrack(C.TRACK_TYPE_TEXT) }
        for (stream in streams.take(2)) {
            assertTrue(onMain { viewModel.trackSelectionHelper.selectSubtitleTrack(stream.index) })
            val selected = previous
            previous = Phase6.awaitValue(TRACK_TIMEOUT, "subtitle ${stream.index} to be shown") {
                onMain { player.selectedTrack(C.TRACK_TYPE_TEXT)?.takeIf { it != selected } }
            }
        }
    }

    private fun ActivityScenario<MainActivity>.playerViewModel(): PlayerViewModel? {
        var viewModel: PlayerViewModel? = null
        onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PlayerFragment>().firstOrNull()
            viewModel = fragment?.let { ViewModelProvider(it)[PlayerViewModel::class.java] }
        }
        return viewModel
    }

    private fun UiDevice.find(selector: BySelector, timeout: Duration): UiObject2 =
        wait(Until.findObject(selector), timeout.inWholeMilliseconds)
            ?: throw AssertionError("No UI element matching $selector after $timeout")

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    /** A key for the selected track of [type], or null when none is selected. */
    private fun Player.selectedTrack(type: Int): String? {
        val group = currentTracks.groups.firstOrNull { it.type == type && it.isSelected } ?: return null
        val format = (0 until group.length).first(group::isTrackSelected).let(group::getTrackFormat)
        return "${format.id}|${format.language}|${format.label}|${format.sampleMimeType}"
    }

    private companion object {
        val ENTRY_TIMEOUT = 2.minutes
        val UI_TIMEOUT = 30.seconds
        val PLAYER_TIMEOUT = 1.minutes
        val TRACK_TIMEOUT = 30.seconds
        val FINISH_TIMEOUT = 2.minutes
        const val FINISH_LEAD_MS = 3000L
    }
}
