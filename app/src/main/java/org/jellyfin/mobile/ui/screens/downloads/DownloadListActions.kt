package org.jellyfin.mobile.ui.screens.downloads

import org.jellyfin.mobile.downloads.DownloadRow

/**
 * Callbacks for the actions that can be performed on the rows of a [DownloadsList].
 */
class DownloadListActions(
    val onOpen: (DownloadRow) -> Unit,
    val onPause: (DownloadRow) -> Unit,
    val onResume: (DownloadRow) -> Unit,
    val onToggleSelection: (DownloadRow) -> Unit = {},
)
