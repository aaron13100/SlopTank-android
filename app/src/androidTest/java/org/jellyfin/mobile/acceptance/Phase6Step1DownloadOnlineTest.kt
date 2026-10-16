package org.jellyfin.mobile.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.JellyfinDatabase
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.OfflineLibrary
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 6, step 1 (network on): sign in, download the item through the app's download manager and let the
 * progress journal read its server baseline while still online.
 */
@RunWith(AndroidJUnit4::class)
class Phase6Step1DownloadOnlineTest {
    @Test
    fun downloadsTheItemWhileOnline() = runBlocking {
        assertTrue("step 1 needs a network", Phase6.hasNetwork())
        assertTrue("step 1 needs the server", Phase6.serverReachable())

        val controller = Phase6.koin<ApiClientController>()
        val preferences = Phase6.koin<AppPreferences>()
        val api = Phase6.koin<ApiClient>()
        val database = Phase6.koin<JellyfinDatabase>()

        controller.setupServer(Phase6.serverUrl)
        val serverId = requireNotNull(preferences.currentServerId)
        val authentication by api.userApi.authenticateUserByName(
            AuthenticateUserByName(username = Phase6.userName, pw = Phase6.password),
        )
        val user = requireNotNull(authentication.user)
        controller.setupUser(serverId, user.id, requireNotNull(authentication.accessToken))
        val userId = requireNotNull(preferences.currentUserId)

        val before = Phase6.serverUserData(Phase6.verifierAuthorization())

        Phase6.koin<DownloadManager>().enqueueItems(api, serverId, userId, listOf(Phase6.itemId))

        val downloaded = Phase6.awaitValue(DOWNLOAD_TIMEOUT, "the download to finish") {
            val row = database.downloadDao.getDownloadByItemId(serverId, userId, Phase6.itemId)
            assertNotNull("the download row exists", row)
            val status = row!!.download.status
            check(status != DownloadStatus.ERROR) { "download failed: ${row.download.errorReason}" }
            row.takeIf { status == DownloadStatus.DOWNLOADED }
        }
        val playable = Phase6.koin<OfflineLibrary>().toPlayable(downloaded)
        assertNotNull("the finished download is playable from local files", playable)
        assertTrue("the local media file is intact", Phase6.koin<OfflineLibrary>().isIntact(playable!!))

        // An offline completion is only reconcilable against a baseline read while online.
        val entry = Phase6.awaitValue(BASELINE_TIMEOUT, "the progress baseline") {
            database.progressDao.getEntry(downloaded.download.id)?.takeIf { it.baseRevision != null }
        }
        assertEquals(before.playCount, entry.basePlayCount)

        Phase6.writeState(
            mapOf(
                KEY_DOWNLOAD_ID to downloaded.download.id.toString(),
                KEY_PLAY_COUNT_BEFORE to before.playCount.toString(),
                KEY_ITEM_NAME to requireNotNull(downloaded.download.item.name),
            ),
        )
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "downloadId"
        const val KEY_PLAY_COUNT_BEFORE = "playCountBefore"
        const val KEY_ITEM_NAME = "itemName"
        private val DOWNLOAD_TIMEOUT = 15.minutes
        private val BASELINE_TIMEOUT = 5.minutes
    }
}
