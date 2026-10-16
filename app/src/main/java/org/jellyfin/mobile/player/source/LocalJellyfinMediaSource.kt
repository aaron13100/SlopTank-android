package org.jellyfin.mobile.player.source

import android.net.Uri
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import java.io.File
import java.util.UUID

/** The local files of a downloaded item. */
data class LocalMediaFiles(
    val mediaUri: Uri,
    /** Downloaded external subtitle files keyed by media stream index. */
    val subtitleFiles: Map<Int, File> = emptyMap(),
)

class LocalJellyfinMediaSource(
    itemId: UUID,
    item: BaseItemDto?,
    sourceInfo: MediaSourceInfo,
    playSessionId: String,
    playbackDetails: PlaybackDetails? = null,
    val localFiles: LocalMediaFiles,
) : JellyfinMediaSource(itemId, item, sourceInfo, playSessionId, playbackDetails) {
    override val playMethod: PlayMethod = PlayMethod.DIRECT_PLAY

    val remoteFileUri: Uri get() = localFiles.mediaUri
}
