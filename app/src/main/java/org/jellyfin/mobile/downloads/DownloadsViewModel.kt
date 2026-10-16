package org.jellyfin.mobile.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.core.DownloadErrorReason
import org.jellyfin.mobile.downloads.progress.ProgressRecorder
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.time.Duration

/**
 * One row of the downloads screen.
 */
data class DownloadRow(
    val download: DownloadEntity,
    val files: DownloadFiles,
    val status: DownloadStatus,
    val errorReason: DownloadErrorReason?,
    /** Progress of the main media file between 0 and 1, or null if its total size is not known. */
    val progress: Float?,
    val bytesDownloaded: Long,
    /** Total size of the main media file, -1 if not known. */
    val totalBytes: Long,
    val imageFile: File?,
    val isPlayable: Boolean,
)

/**
 * Space used by the current account's offline copies and space still free on this device.
 */
data class StorageSummary(
    val usedBytes: Long,
    val availableBytes: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel : ViewModel(), KoinComponent {

    private val downloadDao: DownloadDao by inject()
    private val downloadManager: DownloadManager by inject()
    private val offlineLibrary: OfflineLibrary by inject()
    private val activityEventHandler: ActivityEventHandler by inject()
    private val progressRecorder: ProgressRecorder by inject()

    private val account: Pair<Long, Long>? = offlineLibrary.currentAccount()

    private val downloadFiles: Flow<List<DownloadFiles>> =
        account?.let { (serverId, userId) -> downloadDao.getDownloadsWithFiles(serverId, userId) }
            ?: flowOf(emptyList())

    val rows: StateFlow<List<DownloadRow>> = downloadFiles
        .map { list -> list.map(::toRow) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val storageSummary: StateFlow<StorageSummary?> = downloadFiles
        .mapLatest { computeStorageSummary() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private fun toRow(downloadFiles: DownloadFiles): DownloadRow {
        val download = downloadFiles.download
        val media = downloadFiles.files.firstOrNull { it.type == DownloadFileType.ITEM }
        val bytesDownloaded = media?.bytesDownloaded ?: 0L
        val totalBytes = media?.totalBytes ?: -1L
        val progress = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else null
        return DownloadRow(
            download = download,
            files = downloadFiles,
            status = download.status,
            errorReason = download.errorReason,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            imageFile = offlineLibrary.imageFile(downloadFiles),
            isPlayable = offlineLibrary.toPlayable(downloadFiles) != null,
        )
    }

    private fun computeStorageSummary(): StorageSummary {
        val used = account?.let { (serverId, userId) -> downloadManager.usedBytes(serverId, userId) } ?: 0L
        return StorageSummary(usedBytes = used, availableBytes = downloadManager.availableBytes())
    }

    fun open(row: DownloadRow) {
        if (!row.isPlayable || row.download.item.mediaType != MediaType.VIDEO) return
        viewModelScope.launch {
            // Resume from the offline journal: the newest local position, else the last known server position.
            val resumeTicks = withContext(Dispatchers.IO) { progressRecorder.resumePositionTicks(row.download.id) }
            launchPlayer(row, resumeTicks?.takeIf { it > 0 }?.ticks)
        }
    }

    private fun launchPlayer(row: DownloadRow, startPosition: Duration?) {
        val playOptions = PlayOptions(
            ids = listOf(row.download.itemId),
            mediaSourceId = row.download.itemId.toString(),
            startIndex = 0,
            startPosition = startPosition,
            audioStreamIndex = null,
            subtitleStreamIndex = null,
            playFromDownloads = true,
        )
        activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions))
    }

    fun pause(row: DownloadRow) {
        viewModelScope.launch { downloadManager.pause(row.download.id) }
    }

    fun resume(row: DownloadRow) {
        viewModelScope.launch { downloadManager.resume(row.download.id) }
    }

    fun remove(rows: Collection<DownloadRow>) {
        viewModelScope.launch {
            rows.forEach { row -> downloadManager.remove(row.download) }
        }
    }

    fun removeAll() {
        val (serverId, userId) = account ?: return
        viewModelScope.launch { downloadManager.removeAll(serverId, userId) }
    }
}
