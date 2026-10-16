package org.jellyfin.mobile.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DownloadBroadcastReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        private const val ACTION_DOWNLOAD_PAUSE = "download_pause"
        private const val EXTRA_DOWNLOAD_ID = "download_id"

        fun pauseDownloadIntent(context: Context, downloadId: Long) = Intent(
            context,
            DownloadBroadcastReceiver::class.java,
        ).apply {
            action = ACTION_DOWNLOAD_PAUSE
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
    }

    private val downloadManager by inject<DownloadManager>()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DOWNLOAD_PAUSE) {
            val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return

            val pendingResult = goAsync()
            coroutineScope.launch {
                downloadManager.pause(id)
            }.invokeOnCompletion {
                pendingResult.finish()
            }
        }
    }
}
