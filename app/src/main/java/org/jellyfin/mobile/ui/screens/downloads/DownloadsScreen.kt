package org.jellyfin.mobile.ui.screens.downloads

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadsViewModel

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = viewModel(),
    onBackPressed: () -> Unit = {},
) {
    val downloads by viewModel.downloads.collectAsState()
    val storageLocation by viewModel.storageLocation.collectAsState()
    val storageLocationAccessible by viewModel.storageLocationAccessible.collectAsState()
    val selection = remember { mutableStateSetOf<Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val selectionMode = selection.isNotEmpty()

    val storageLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.changeStorageLocation(uri)
    }

    BackHandler(enabled = selectionMode) {
        selection.clear()
    }

    if (showDeleteConfirm) {
        RemoveSelectedDownloadsDialog(
            downloads = downloads,
            selection = selection,
            onRemove = { download, deleteFiles -> viewModel.removeDownload(download, deleteFiles = deleteFiles) },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            DownloadsTopBar(
                downloads = downloads,
                selection = selection,
                onBackPressed = onBackPressed,
                onDeleteClick = { showDeleteConfirm = true },
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                StorageLocationWarning(
                    storageLocationSet = storageLocation != null,
                    storageLocationAccessible = storageLocationAccessible,
                    onFix = { storageLocationPicker.launch(null) },
                )

                DownloadsListOrEmpty(
                    downloads = downloads,
                    selection = selection,
                    actions = DownloadListActions(
                        onOpen = { viewModel.openDownload(it) },
                        onDownload = { viewModel.download(it) },
                        onToggleSelection = { download -> selection.toggle(download.id) },
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

@Composable
private fun RemoveSelectedDownloadsDialog(
    downloads: List<DownloadFiles>,
    selection: MutableSet<Long>,
    onRemove: (download: DownloadEntity, deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val downloadsToRemove = remember(selection, downloads) {
        downloads
            .map { it.download }
            .filter { it.id in selection }
    }
    DownloadRemoveDialog(
        downloads = downloadsToRemove,
        onConfirm = { keepLocalFiles ->
            downloadsToRemove.forEach { download ->
                onRemove(download, !keepLocalFiles)
            }
            selection.clear()
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

private fun MutableSet<Long>.toggle(id: Long) {
    if (contains(id)) {
        remove(id)
    } else {
        add(id)
    }
}

@Composable
private fun StorageLocationWarning(
    storageLocationSet: Boolean,
    storageLocationAccessible: Boolean,
    onFix: () -> Unit,
) {
    if (!storageLocationSet) {
        StorageLocationNotSetCard(
            onFix = onFix,
            modifier = Modifier.padding(16.dp),
        )
    } else if (!storageLocationAccessible) {
        StorageLocationInaccessibleCard(
            onFix = onFix,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DownloadsListOrEmpty(
    downloads: List<DownloadFiles>,
    selection: Set<Long>,
    actions: DownloadListActions,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) {
        DownloadsEmpty(modifier = modifier)
    } else {
        DownloadsList(
            downloads = downloads,
            actions = actions,
            selection = selection,
            modifier = modifier,
        )
    }
}

@Composable
private fun DownloadsTopBar(
    downloads: List<DownloadFiles>,
    selection: MutableSet<Long>,
    onBackPressed: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val selectionMode = selection.isNotEmpty()

    TopAppBar(
        title = {
            DownloadsTitle(
                selectionMode = selectionMode,
                selectedCount = selection.size,
            )
        },
        navigationIcon = {
            DownloadsNavigationIcon(
                selectionMode = selectionMode,
                onClearSelection = { selection.clear() },
                onBackPressed = onBackPressed,
            )
        },
        actions = {
            DownloadsSelectionActions(
                downloads = downloads,
                selection = selection,
                onDeleteClick = onDeleteClick,
            )
        },
        backgroundColor = Color.Transparent,
    )
}

@Composable
private fun DownloadsTitle(
    selectionMode: Boolean,
    selectedCount: Int,
) {
    AnimatedContent(
        targetState = selectionMode,
        transitionSpec = {
            if (targetState) {
                (slideInVertically { height -> height } + fadeIn()) togetherWith
                    (slideOutVertically { height -> -height } + fadeOut())
            } else {
                (slideInVertically { height -> -height } + fadeIn()) togetherWith
                    (slideOutVertically { height -> height } + fadeOut())
            }
        },
        label = "TitleAnimation",
    ) { isSelectionMode ->
        if (isSelectionMode) {
            Text(text = stringResource(R.string.selected_count, selectedCount))
        } else {
            Text(text = stringResource(R.string.downloads))
        }
    }
}

@Composable
private fun DownloadsNavigationIcon(
    selectionMode: Boolean,
    onClearSelection: () -> Unit,
    onBackPressed: () -> Unit,
) {
    Crossfade(
        targetState = selectionMode,
        label = "NavIconAnimation",
    ) { isSelectionMode ->
        if (isSelectionMode) {
            IconButton(
                onClick = { onClearSelection() },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                )
            }
        } else {
            IconButton(
                onClick = { onBackPressed() },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun DownloadsSelectionActions(
    downloads: List<DownloadFiles>,
    selection: MutableSet<Long>,
    onDeleteClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row {
        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                    )
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = null,
                        )
                    }
                    DownloadsSelectionMenu(
                        expanded = showMenu,
                        downloads = downloads,
                        selection = selection,
                        onDismiss = { showMenu = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsSelectionMenu(
    expanded: Boolean,
    downloads: List<DownloadFiles>,
    selection: MutableSet<Long>,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (selection.size < downloads.size) {
            DropdownMenuItem(
                onClick = {
                    selection.addAll(downloads.map { it.download.id })
                    onDismiss()
                },
            ) {
                Text(text = stringResource(R.string.select_all))
            }
        }

        if (selection.isNotEmpty()) {
            DropdownMenuItem(
                onClick = {
                    selection.clear()
                    onDismiss()
                },
            ) {
                Text(text = stringResource(R.string.deselect_all))
            }
        }
    }
}
