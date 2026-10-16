package org.jellyfin.mobile.player.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import coil3.toBitmap
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.coil.SubsetTransformation
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.TrickplayInfo
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

class TrickplayHelper(
    private val thumbnailContainer: View,
    private val thumbnailView: AppCompatImageView,
    private val seekBarContainer: View,
    private val chapterNameView: AppCompatTextView,
    private val timeView: AppCompatTextView,
) : KoinComponent {
    private val api: ApiClient by inject()
    private val imageLoader: ImageLoader by inject()
    private val context = thumbnailView.context
    private val handler = Handler(Looper.getMainLooper())
    private val thumbnailDisplayHeight = context.resources.getDimensionPixelSize(R.dimen.trickplay_thumbnail_height)
    private var thumbnailDisplayWidth = 0

    private var trickPlayInfo: TrickplayInfo? = null
    private var itemId: UUID? = null
    private var mediaSourceId: UUID? = null
    private var durationMs: Long = 0L
    private var currentRequest: Disposable? = null
    private var pendingRequest: Runnable? = null
    private var pendingTile = -1
    private var lastDispatchedTile = -1
    private var isScrubbing = false
    private var nextDispatchAt = 0L
    private var chapters: List<ChapterInfo>? = null

    fun onMediaSourceChanged(source: JellyfinMediaSource?) {
        trickPlayInfo = null
        itemId = null
        mediaSourceId = null
        durationMs = 0L
        pendingTile = -1
        lastDispatchedTile = -1
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingRequest = null
        chapters = null
        thumbnailContainer.visibility = View.GONE

        // Downloads play without the network, so no trickplay tiles are fetched for them.
        if (source is LocalJellyfinMediaSource) return

        val item = source?.item
        val resolvedSourceId = source?.id
        val resolvedMediaSourceId = resolvedSourceId?.toUUIDOrNull()
        val resolvedTrickPlayInfo = item?.trickplay?.get(resolvedSourceId)?.values?.firstOrNull()
        if (item == null || resolvedMediaSourceId == null || resolvedTrickPlayInfo == null) return

        trickPlayInfo = resolvedTrickPlayInfo
        itemId = item.id
        mediaSourceId = resolvedMediaSourceId
        durationMs = source.runTime.inWholeMilliseconds
        chapters = item.chapters
        val aspectRatio = resolvedTrickPlayInfo.width.toFloat() / resolvedTrickPlayInfo.height
        thumbnailDisplayWidth = (thumbnailDisplayHeight * aspectRatio).roundToInt()
        thumbnailView.updateLayoutParams<ViewGroup.LayoutParams> { width = thumbnailDisplayWidth }
    }

    fun onScrubMove(position: Long) {
        isScrubbing = true
        if (trickPlayInfo == null || itemId == null || mediaSourceId == null) return
        if (durationMs <= 0) return

        val resolvedTrickPlayInfo = trickPlayInfo!!
        val resolvedItemId = itemId!!
        val resolvedMediaSourceId = mediaSourceId!!

        // Calculate trickplay tile position and offset based on scrubberposition
        val currentTile = position.floorDiv(resolvedTrickPlayInfo.interval).toInt()
        val tileSize = resolvedTrickPlayInfo.tileWidth * resolvedTrickPlayInfo.tileHeight
        val tileOffset = currentTile % tileSize
        val tileIndex = currentTile / tileSize
        val tileOffsetX = tileOffset % resolvedTrickPlayInfo.tileWidth
        val tileOffsetY = tileOffset / resolvedTrickPlayInfo.tileWidth
        val offsetX = tileOffsetX * resolvedTrickPlayInfo.width
        val offsetY = tileOffsetY * resolvedTrickPlayInfo.height

        // Always update horizontal position regardless of tile change, centered above scrubber
        val fraction = position.toFloat() / durationMs.toFloat()
        val scrubberX = seekBarContainer.x + fraction * seekBarContainer.width
        val clampMin = seekBarContainer.x
        val clampMax = (seekBarContainer.x + seekBarContainer.width - thumbnailDisplayWidth).coerceAtLeast(clampMin)
        thumbnailContainer.x = (scrubberX - thumbnailDisplayWidth / 2f).coerceIn(clampMin, clampMax)

        // Update chapter name and timestamp on every move
        val positionTicks = position * Constants.TICKS_PER_MILLISECOND
        val chapterName = chapters?.lastOrNull { it.startPositionTicks <= positionTicks }?.name
        chapterNameView.isVisible = !chapterName.isNullOrEmpty()
        if (!chapterName.isNullOrEmpty()) chapterNameView.text = chapterName
        timeView.text = formatPositionAsElapsedTime(position)

        // Same tile already pending or already displayed - position updated above, nothing else to do
        if (currentTile == pendingTile || currentTile == lastDispatchedTile) return

        // Cancel previous pending request and schedule a new one for the latest tile
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingTile = currentTile

        val url = api.trickplayApi.getTrickplayTileImageUrl(
            itemId = resolvedItemId,
            width = resolvedTrickPlayInfo.width,
            index = tileIndex,
            mediaSourceId = resolvedMediaSourceId,
        )

        val runnable = Runnable {
            dispatchRequest(
                TileRequest(
                    url = url,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    tileWidth = resolvedTrickPlayInfo.width,
                    tileHeight = resolvedTrickPlayInfo.height,
                    tile = currentTile,
                ),
            )
        }
        pendingRequest = runnable

        // Run immediately if next dispatch time has passed, otherwise schedule for the remaining time
        handler.postAtTime(
            runnable,
            maxOf(SystemClock.uptimeMillis(), nextDispatchAt),
        )
    }

    private fun dispatchRequest(request: TileRequest) {
        lastDispatchedTile = request.tile
        pendingTile = -1
        pendingRequest = null
        nextDispatchAt = SystemClock.uptimeMillis() + Constants.TRICKPLAY_TILE_REFRESH_WINDOW_MS

        currentRequest?.dispose()
        currentRequest = imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(request.url)
                .size(Size.ORIGINAL)
                .maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
                .httpHeaders(
                    NetworkHeaders.Builder()
                        .set(
                            "Authorization",
                            AuthorizationHeaderBuilder.buildHeader(
                                clientName = api.clientInfo.name,
                                clientVersion = api.clientInfo.version,
                                deviceId = api.deviceInfo.id,
                                deviceName = api.deviceInfo.name,
                                accessToken = api.accessToken,
                            ),
                        )
                        .build(),
                )
                .transformations(
                    SubsetTransformation(request.offsetX, request.offsetY, request.tileWidth, request.tileHeight),
                )
                .target(
                    onSuccess = { image ->
                        if (isScrubbing) {
                            thumbnailView.setImageBitmap(image.toBitmap())
                            thumbnailContainer.visibility = View.VISIBLE
                        }
                    },
                )
                .build(),
        )
    }

    fun onScrubStop() {
        isScrubbing = false
        pendingRequest?.let { handler.removeCallbacks(it) }
        pendingRequest = null
        pendingTile = -1
        lastDispatchedTile = -1
        nextDispatchAt = 0L
        currentRequest?.dispose()
        currentRequest = null
        thumbnailContainer.visibility = View.GONE
    }

    private fun formatPositionAsElapsedTime(positionMs: Long): String {
        // Add half a second before truncating to match Media3's time display round-to-nearest behavior
        return DateUtils.formatElapsedTime((positionMs + ROUND_TO_NEAREST_THRESHOLD_MS).milliseconds.inWholeSeconds)
    }

    /**
     * A request for a single trickplay thumbnail, cut out of a tile image at [url].
     */
    private data class TileRequest(
        val url: String,
        val offsetX: Int,
        val offsetY: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val tile: Int,
    )

    private companion object {
        const val ROUND_TO_NEAREST_THRESHOLD_MS = 500L
    }
}
