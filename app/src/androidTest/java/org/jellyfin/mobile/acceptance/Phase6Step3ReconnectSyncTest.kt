package org.jellyfin.mobile.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.acceptance.Phase6Step1DownloadOnlineTest.Companion.KEY_DOWNLOAD_ID
import org.jellyfin.mobile.acceptance.Phase6Step1DownloadOnlineTest.Companion.KEY_PLAY_COUNT_BEFORE
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.downloads.progress.ProgressSyncWorker
import org.jellyfin.mobile.downloads.progress.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 6, step 3 (airplane mode off again): the sync the app scheduled while offline runs on its own once a
 * network is back. The server must count the offline completion exactly once, also after further syncs.
 */
@RunWith(AndroidJUnit4::class)
class Phase6Step3ReconnectSyncTest {
    private val database = Phase6.koin<JellyfinDatabase>()

    @Test
    fun syncsTheOfflineCompletionExactlyOnce() = runBlocking {
        val state = Phase6.readState()
        val downloadId = state.getValue(KEY_DOWNLOAD_ID).toLong()
        val playCountBefore = state.getValue(KEY_PLAY_COUNT_BEFORE).toInt()

        Phase6.awaitValue(NETWORK_TIMEOUT, "the network and the server") {
            Unit.takeIf { Phase6.hasNetwork() && Phase6.serverReachable() }
        }

        // No manual trigger: the work scheduled after offline playback must run by itself.
        val synced = Phase6.awaitValue(SYNC_TIMEOUT, "the journal entry to be acknowledged") {
            database.progressDao.getEntry(downloadId)
                ?.takeIf { it.state == SyncState.CLEAN.name && !it.dirty && it.operationId == null }
        }
        assertEquals("the server saw the play count the journal started from", playCountBefore, synced.basePlayCount)

        val authorization = Phase6.verifierAuthorization()
        val afterSync = Phase6.serverUserData(authorization)
        assertEquals("the offline completion counted once", playCountBefore + 1, afterSync.playCount)
        assertTrue(afterSync.played)

        // Exactly once: more sync runs (each app start and playback stop schedules one) change nothing.
        repeat(EXTRA_SYNC_RUNS) {
            ProgressSyncWorker.schedule(Phase6.context)
            awaitSyncWorkFinished()
        }
        val afterReplays = Phase6.serverUserData(authorization)
        assertEquals("no second count after further syncs", playCountBefore + 1, afterReplays.playCount)
        assertEquals(SyncState.CLEAN.name, database.progressDao.getEntry(downloadId)!!.state)
    }

    private suspend fun awaitSyncWorkFinished() {
        val workManager = WorkManager.getInstance(Phase6.context)
        Phase6.awaitValue(SYNC_TIMEOUT, "the sync work to finish") {
            val infos = withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(SYNC_WORK_NAME).get() }
            Unit.takeIf { infos.isNotEmpty() && infos.all { it.state.isFinished } }
        }
    }

    private companion object {
        /** Unique work name used by [ProgressSyncWorker.schedule]. */
        const val SYNC_WORK_NAME = "offline-progress-sync"
        const val EXTRA_SYNC_RUNS = 2
        val NETWORK_TIMEOUT = 2.minutes
        val SYNC_TIMEOUT = 10.minutes
    }
}
