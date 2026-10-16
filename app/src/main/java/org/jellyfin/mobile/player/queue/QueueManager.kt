package org.jellyfin.mobile.player.queue

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerWebPreferences
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.MediaSourceRequest
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.source.PlaybackDetails
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

class QueueManager(
    private val viewModel: PlayerViewModel,
) : KoinComponent {
    private val apiClient: ApiClient = get()
    private val mediaSourceFactory = QueueMediaSourceFactory(apiClient)
    private val mediaSourceResolver: MediaSourceResolver by inject()
    private val deviceProfileBuilder: DeviceProfileBuilder by inject()
    private val downloadDao: DownloadDao by inject()
    private val deviceProfile = deviceProfileBuilder.getDeviceProfile()

    private var currentQueue: List<UUID> = emptyList()
    private var currentQueueIndex: Int = 0

    private var playbackRetries = 0
    private var lastPlaybackError = 0L

    private val _currentMediaSource: MutableLiveData<JellyfinMediaSource> = MutableLiveData()
    val currentMediaSource: LiveData<JellyfinMediaSource>
        get() = _currentMediaSource

    fun getCurrentMediaSourceOrNull(): JellyfinMediaSource? = currentMediaSource.value

    /**
     * Handle initial playback options from fragment.
     * Start of a playback session that can contain one or multiple played videos.
     *
     * @return an error of type [PlayerException] or null on success.
     */
    suspend fun initializePlaybackQueue(
        playOptions: PlayOptions,
        preferences: PlayerWebPreferences? = null
    ): PlayerException? {
        currentQueue = playOptions.ids
        currentQueueIndex = playOptions.startIndex
        resetPlaybackFallback()

        val itemId = when {
            currentQueue.isNotEmpty() -> currentQueue[currentQueueIndex]
            else -> playOptions.mediaSourceId?.toUUIDOrNull()
        } ?: return PlayerException.InvalidPlayOptions()

        val maxStreamingBitrate = preferences?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val endpoint by apiClient.systemApi.getEndpointInfo()
                    if (endpoint.isInNetwork) {
                        it.maxStreamingBitrateLocal
                    } else {
                        it.maxStreamingBitrateRemote
                    }
                }
            }.getOrNull()
        }

        when (playOptions.playFromDownloads) {
            true -> playOptions.mediaSourceId?.let {
                startDownloadPlayback(
                    itemId = itemId,
                    playWhenReady = true,
                )
            }
            else -> startRemotePlayback(
                MediaSourceRequest(
                    itemId = itemId,
                    mediaSourceId = playOptions.mediaSourceId,
                    maxStreamingBitrate = maxStreamingBitrate,
                    startTime = playOptions.startPosition,
                    audioStreamIndex = playOptions.audioStreamIndex,
                    subtitleStreamIndex = playOptions.subtitleStreamIndex,
                ),
                playWhenReady = true,
            )
        }

        return null
    }

    private suspend fun startDownloadPlayback(
        itemId: UUID,
        startTime: Duration? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        playWhenReady: Boolean = true,
    ): PlayerException? {
        val download = withContext(Dispatchers.IO) {
            downloadDao.getDownloadByItemId(itemId)
        } ?: return PlayerException.UnsupportedContent()

        val files = withContext(Dispatchers.IO) {
            downloadDao.getFiles(download.id)
        }

        val mainFile = files.find { it.type == DownloadFileType.ITEM } ?: return PlayerException.NetworkFailure()

        val mediaSource = LocalJellyfinMediaSource(
            itemId = download.itemId,
            item = download.item,
            sourceInfo = download.item.mediaSources!!.first(),
            playSessionId = download.id.toString(),
            playbackDetails = PlaybackDetails(startTime, audioStreamIndex, subtitleStreamIndex),
            remoteFileUri = mainFile.uri,
        )
        startTime?.let { duration -> mediaSource.startTime = duration }
        audioStreamIndex?.let { index -> mediaSource.selectAudioStream(mediaSource.audioStreams[index]) }
        subtitleStreamIndex?.let { index -> mediaSource.selectSubtitleStream(mediaSource.subtitleStreams[index]) }

        _currentMediaSource.value = mediaSource

        // Load new media source
        viewModel.load(mediaSource, mediaSourceFactory.prepareStreams(mediaSource), playWhenReady)

        return null
    }

    /**
     * Play a specific media item specified by the [request]'s item id and media source id.
     *
     * @return an error of type [PlayerException] or null on success.
     */
    private suspend fun startRemotePlayback(
        request: MediaSourceRequest,
        playWhenReady: Boolean = true,
    ): PlayerException? {
        mediaSourceResolver.resolveMediaSource(
            request.copy(deviceProfile = deviceProfile),
        ).onSuccess { jellyfinMediaSource ->
            // Ensure transcoding of the current element is stopped
            getCurrentMediaSourceOrNull()?.let { oldMediaSource ->
                viewModel.stopTranscoding(oldMediaSource as RemoteJellyfinMediaSource)
            }

            _currentMediaSource.value = jellyfinMediaSource

            // Load new media source
            val exoMediaSource = mediaSourceFactory.prepareStreams(jellyfinMediaSource)
            viewModel.load(jellyfinMediaSource, exoMediaSource, playWhenReady)
        }.onFailure { error ->
            // Should always be of this type, other errors are silently dropped
            return error as? PlayerException
        }
        return null
    }

    /**
     * Reinitialize current media source without changing settings
     */
    fun tryRestartPlayback() {
        with(getCurrentMediaSourceOrNull()) {
            when (this) {
                is LocalJellyfinMediaSource -> mediaSourceFactory.prepareStreams(this)
                is RemoteJellyfinMediaSource -> mediaSourceFactory.prepareStreams(this)
                null -> return
            }.let {
                viewModel.load(this, it, playWhenReady = true)
            }
        }
    }

    private fun resetPlaybackFallback() {
        playbackRetries = 0
        lastPlaybackError = 0L
        viewModel.cancelFallbackRetry()
    }

    /**
     * Retry playback with progressively degraded settings to work around source errors.
     *
     * Retry 1: no constraints — lets the server decide freely.
     * Retry 2: disable direct play, allowing the server to fall back to direct stream.
     * Retry 3: disable direct stream too, forcing the server to transcode.
     *
     * @param startPosition The position at which to resume playback after the retry.
     * @return true if a retry was initiated, false if retries are exhausted or not applicable.
     */
    suspend fun restartPlaybackWithFallback(startPosition: Duration): Boolean {
        val currentMediaSource = getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource ?: return false

        val now = System.currentTimeMillis()
        if (playbackRetries > 0 && now - lastPlaybackError > PLAYBACK_RETRY_RESET_MS) {
            Timber.i("Playback stabilized, resetting retry count from %d", playbackRetries)
            playbackRetries = 0
        }

        playbackRetries++
        lastPlaybackError = now

        if (playbackRetries > MAX_PLAYBACK_RETRIES) return false

        Timber.i("Retrying playback (attempt %d of %d)", playbackRetries, MAX_PLAYBACK_RETRIES)

        // If already transcoding, only retry once (for transient errors); flags are null on
        // retry 1 anyway, so no special-casing needed in the startRemotePlayback call.
        if (currentMediaSource.playMethod == PlayMethod.TRANSCODE && playbackRetries > 1) return false

        return startRemotePlayback(
            MediaSourceRequest(
                itemId = currentMediaSource.itemId,
                mediaSourceId = currentMediaSource.id,
                maxStreamingBitrate = currentMediaSource.maxStreamingBitrate,
                startTime = startPosition,
                audioStreamIndex = currentMediaSource.selectedAudioStreamIndex,
                subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
                enableDirectPlay = if (playbackRetries > 1) false else null,
                enableDirectStream = if (playbackRetries > 2) false else null,
            ),
            playWhenReady = true,
        ) == null
    }

    /**
     * Change the maximum bitrate to the specified value.
     */
    suspend fun changeBitrate(bitrate: Int?): Boolean {
        val currentMediaSource = getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource ?: return false

        // Bitrate didn't change, ignore
        if (currentMediaSource.maxStreamingBitrate == bitrate) return true

        val currentPlayState = viewModel.getStateAndPause() ?: return false

        return startRemotePlayback(
            MediaSourceRequest(
                itemId = currentMediaSource.itemId,
                mediaSourceId = currentMediaSource.id,
                maxStreamingBitrate = bitrate,
                startTime = currentPlayState.position,
                audioStreamIndex = currentMediaSource.selectedAudioStreamIndex,
                subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
            ),
            playWhenReady = currentPlayState.playWhenReady,
        ) == null
    }

    fun hasPrevious(): Boolean = currentQueue.isNotEmpty() && currentQueueIndex > 0

    fun hasNext(): Boolean = currentQueue.isNotEmpty() && currentQueueIndex < currentQueue.lastIndex

    suspend fun previous(): Boolean {
        if (!hasPrevious()) return false

        val currentMediaSource = getCurrentMediaSourceOrNull() as? RemoteJellyfinMediaSource ?: return false

        resetPlaybackFallback()

        startRemotePlayback(
            MediaSourceRequest(
                itemId = currentQueue[--currentQueueIndex],
                mediaSourceId = null,
                maxStreamingBitrate = currentMediaSource.maxStreamingBitrate,
            ),
        )
        return true
    }

    suspend fun next(): Boolean {
        if (!hasNext()) return false

        resetPlaybackFallback()

        when (val currentMediaSource = getCurrentMediaSourceOrNull()) {
            is LocalJellyfinMediaSource -> startDownloadPlayback(
                itemId = currentQueue[++currentQueueIndex],
                playWhenReady = true,
            )
            is RemoteJellyfinMediaSource -> startRemotePlayback(
                MediaSourceRequest(
                    itemId = currentQueue[++currentQueueIndex],
                    mediaSourceId = null,
                    maxStreamingBitrate = currentMediaSource.maxStreamingBitrate,
                ),
            )
            null -> return false
        }
        return true
    }

    /**
     * Switch to the specified [audio stream][stream] and restart playback, for example while transcoding.
     *
     * @return true if playback was restarted with the new selection.
     */
    suspend fun selectAudioStreamAndRestartPlayback(stream: MediaStream): Boolean {
        require(stream.type == MediaStreamType.AUDIO)
        val currentPlayState = viewModel.getStateAndPause() ?: return false
        resetPlaybackFallback()

        when (val currentMediaSource = getCurrentMediaSourceOrNull()) {
            is LocalJellyfinMediaSource -> startDownloadPlayback(
                itemId = currentMediaSource.itemId,
                startTime = currentPlayState.position,
                audioStreamIndex = stream.index,
                subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
                playWhenReady = currentPlayState.playWhenReady,
            )
            is RemoteJellyfinMediaSource -> startRemotePlayback(
                MediaSourceRequest(
                    itemId = currentMediaSource.itemId,
                    mediaSourceId = currentMediaSource.id,
                    maxStreamingBitrate = currentMediaSource.maxStreamingBitrate,
                    startTime = currentPlayState.position,
                    audioStreamIndex = stream.index,
                    subtitleStreamIndex = currentMediaSource.selectedSubtitleStreamIndex,
                ),
                playWhenReady = currentPlayState.playWhenReady,
            )
            null -> return false
        }
        return true
    }

    /**
     * Switch to the specified [subtitle stream][stream] and restart playback,
     * for example because the selected subtitle has to be encoded into the video.
     *
     * @param stream The subtitle stream to select, or null to disable subtitles.
     * @return true if playback was restarted with the new selection.
     */
    suspend fun selectSubtitleStreamAndRestartPlayback(stream: MediaStream?): Boolean {
        require(stream == null || stream.type == MediaStreamType.SUBTITLE)
        val currentPlayState = viewModel.getStateAndPause() ?: return false
        resetPlaybackFallback()

        // -1 disables subtitles, null would select the default subtitle
        val subtitleStreamIndex = stream?.index ?: -1
        when (val mediaSource = getCurrentMediaSourceOrNull()) {
            is LocalJellyfinMediaSource -> startDownloadPlayback(
                itemId = mediaSource.itemId,
                startTime = currentPlayState.position,
                audioStreamIndex = mediaSource.selectedAudioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                playWhenReady = currentPlayState.playWhenReady,
            )
            is RemoteJellyfinMediaSource -> startRemotePlayback(
                MediaSourceRequest(
                    itemId = mediaSource.itemId,
                    mediaSourceId = mediaSource.id,
                    maxStreamingBitrate = mediaSource.maxStreamingBitrate,
                    startTime = currentPlayState.position,
                    audioStreamIndex = mediaSource.selectedAudioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                ),
                playWhenReady = currentPlayState.playWhenReady,
            )
            null -> return false
        }
        return true
    }

    companion object {
        private const val MAX_PLAYBACK_RETRIES = 3
        private const val PLAYBACK_RETRY_RESET_MS = 30_000L
    }
}
