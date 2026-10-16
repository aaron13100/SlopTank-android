package org.jellyfin.mobile.ui.screens.downloads

import android.content.Context
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.ListItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadRow
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.StorageSummary
import org.jellyfin.mobile.downloads.core.DownloadErrorReason
import java.io.File
import kotlin.math.roundToInt

@Composable
fun DownloadsList(
    rows: List<DownloadRow>,
    actions: DownloadListActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
    selection: Set<Long> = emptySet(),
) {
    val selectionMode = selection.isNotEmpty()

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        items(
            rows,
            key = { it.download.id },
        ) { row ->
            DownloadItem(
                row = row,
                isSelected = selection.contains(row.download.id),
                selectionMode = selectionMode,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DownloadItem(
    row: DownloadRow,
    isSelected: Boolean,
    selectionMode: Boolean,
    actions: DownloadListActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    ListItem(
        modifier = modifier
            .combinedClickable(
                onClick = {
                    if (selectionMode) actions.onToggleSelection(row) else onRowClick(row, actions)
                },
                onLongClick = { actions.onToggleSelection(row) },
            ),
        text = {
            val name = remember(row.download, context) { row.download.getDisplayName(context).orEmpty() }
            Text(
                text = name,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        },
        icon = {
            DownloadItemIcon(
                imageFile = row.imageFile,
                isSelected = isSelected,
                selectionMode = selectionMode,
            )
        },
        secondaryText = {
            DownloadItemSecondaryText(row = row)
        },
    )
}

private fun onRowClick(row: DownloadRow, actions: DownloadListActions) {
    when {
        row.isPlayable -> actions.onOpen(row)
        row.status == DownloadStatus.DOWNLOADING || row.status == DownloadStatus.QUEUED -> actions.onPause(row)
        row.status == DownloadStatus.PAUSED -> actions.onResume(row)
        row.status == DownloadStatus.ERROR && row.errorReason != DownloadErrorReason.NOT_PORTABLE ->
            actions.onResume(row)
        else -> Unit
    }
}

@Composable
private fun DownloadItemIcon(
    imageFile: File?,
    isSelected: Boolean,
    selectionMode: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        AsyncImage(
            model = imageFile,
            placeholder = painterResource(R.drawable.ic_local_movies_white_64),
            fallback = painterResource(R.drawable.ic_local_movies_white_64),
            error = painterResource(R.drawable.ic_local_movies_white_64),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(
                width = 64.dp,
                height = 64.dp,
            ),
        )
    }
}

@Composable
private fun DownloadItemSecondaryText(row: DownloadRow) {
    val context = LocalContext.current
    val progress = row.progress?.takeIf {
        row.status == DownloadStatus.DOWNLOADING || row.status == DownloadStatus.PAUSED
    }

    Column {
        Text(
            text = statusText(context, row),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        if (row.status == DownloadStatus.ERROR && row.errorReason == DownloadErrorReason.NOT_PORTABLE) {
            Text(
                text = stringResource(R.string.download_not_portable_description),
                style = MaterialTheme.typography.caption,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

private fun statusText(context: Context, row: DownloadRow): String {
    val percent = row.progress?.let { (it * PERCENT).roundToInt() }
    val downloaded = Formatter.formatShortFileSize(context, row.bytesDownloaded)
    return when (row.status) {
        DownloadStatus.QUEUED -> context.getString(R.string.download_status_queued)
        DownloadStatus.DOWNLOADING -> if (percent != null) {
            val total = Formatter.formatShortFileSize(context, row.totalBytes)
            context.getString(R.string.download_status_downloading, percent, downloaded, total)
        } else {
            context.getString(R.string.download_status_downloading_unknown, downloaded)
        }
        DownloadStatus.PAUSED -> if (percent != null) {
            context.getString(R.string.download_status_paused, percent)
        } else {
            context.getString(R.string.download_status_paused_unknown)
        }
        DownloadStatus.DOWNLOADED -> {
            val size = row.files.files.sumOf { file -> maxOf(file.bytesDownloaded, file.totalBytes, 0L) }
            val text = context.getString(
                R.string.download_status_downloaded,
                Formatter.formatShortFileSize(context, size)
            )
            // Still playable offline; the server copy is gone.
            if (row.errorReason == DownloadErrorReason.SERVER_ITEM_GONE) {
                context.getString(R.string.download_status_removed_from_server, text)
            } else {
                text
            }
        }
        DownloadStatus.ERROR,
        DownloadStatus.CANCELLED,
        -> if (row.errorReason == DownloadErrorReason.NOT_PORTABLE) {
            context.getString(R.string.download_status_not_portable)
        } else {
            context.getString(R.string.download_status_failed, context.getString(errorReasonText(row.errorReason)))
        }
    }
}

private fun errorReasonText(reason: DownloadErrorReason?): Int = when (reason) {
    DownloadErrorReason.NETWORK -> R.string.download_error_network
    DownloadErrorReason.INSUFFICIENT_STORAGE -> R.string.download_error_insufficient_storage
    DownloadErrorReason.VALIDATION_FAILED -> R.string.download_error_validation_failed
    DownloadErrorReason.SERVER_ITEM_GONE -> R.string.download_error_server_item_gone
    DownloadErrorReason.AUTH_EXPIRED -> R.string.download_error_auth_expired
    DownloadErrorReason.NOT_PORTABLE -> R.string.download_status_not_portable
    null -> R.string.download_error_unknown
}

@Composable
fun StorageSummaryLine(
    summary: StorageSummary,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(
            R.string.downloads_storage_summary,
            Formatter.formatShortFileSize(context, summary.usedBytes),
            Formatter.formatShortFileSize(context, summary.availableBytes),
        ),
        style = MaterialTheme.typography.body2,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private const val PERCENT = 100
