package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadEntity

@Composable
fun DownloadRemoveDialog(
    downloads: List<DownloadEntity>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val titleRes = if (downloads.size == 1) {
                R.string.download_remove_title_single
            } else {
                R.string.download_remove_title_multiple
            }
            Text(text = stringResource(titleRes))
        },
        text = {
            Column {
                val name = remember(downloads, context) {
                    downloads
                        .firstOrNull()
                        .takeIf { downloads.size == 1 }
                        ?.getDisplayName(context)
                        .orEmpty()
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.download_remove_description,
                        downloads.size,
                        downloads.size,
                        name,
                    ),
                )
                Text(
                    text = stringResource(R.string.download_remove_device_only),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.download_cancel))
            }
        },
    )
}

@Composable
fun DownloadRemoveAllDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.downloads_remove_all)) },
        text = { Text(text = stringResource(R.string.downloads_remove_all_description)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.download_cancel))
            }
        },
    )
}
