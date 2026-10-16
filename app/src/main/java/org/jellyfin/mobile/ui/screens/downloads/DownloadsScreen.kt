package org.jellyfin.mobile.ui.screens.downloads

import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadRow
import org.jellyfin.mobile.downloads.DownloadsViewModel

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = viewModel(),
    onBackPressed: () -> Unit = {},
) {
    val rows by viewModel.rows.collectAsState()
    val storageSummary by viewModel.storageSummary.collectAsState()
    val selection = remember { mutableStateSetOf<Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRemoveAllConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = selection.isNotEmpty()) {
        selection.clear()
    }

    if (showDeleteConfirm) {
        RemoveSelectedDownloadsDialog(
            rows = rows,
            selection = selection,
            onRemove = { viewModel.remove(it) },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showRemoveAllConfirm) {
        DownloadRemoveAllDialog(
            onConfirm = {
                viewModel.removeAll()
                selection.clear()
                showRemoveAllConfirm = false
            },
            onDismiss = { showRemoveAllConfirm = false },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            DownloadsTopBar(
                rows = rows,
                selection = selection,
                onBackPressed = onBackPressed,
                onDeleteClick = { showDeleteConfirm = true },
                onRemoveAllClick = { showRemoveAllConfirm = true },
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                storageSummary?.let { summary -> StorageSummaryLine(summary = summary) }

                DownloadsListOrEmpty(
                    rows = rows,
                    selection = selection,
                    actions = rememberListActions(viewModel, selection),
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

@Composable
private fun rememberListActions(
    viewModel: DownloadsViewModel,
    selection: MutableSet<Long>,
): DownloadListActions = remember(viewModel, selection) {
    DownloadListActions(
        onOpen = { viewModel.open(it) },
        onPause = { viewModel.pause(it) },
        onResume = { viewModel.resume(it) },
        onToggleSelection = { row -> selection.toggle(row.download.id) },
    )
}

@Composable
private fun RemoveSelectedDownloadsDialog(
    rows: List<DownloadRow>,
    selection: MutableSet<Long>,
    onRemove: (List<DownloadRow>) -> Unit,
    onDismiss: () -> Unit,
) {
    val rowsToRemove = remember(selection, rows) {
        rows.filter { it.download.id in selection }
    }
    DownloadRemoveDialog(
        downloads = rowsToRemove.map { it.download },
        onConfirm = {
            onRemove(rowsToRemove)
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
private fun DownloadsListOrEmpty(
    rows: List<DownloadRow>,
    selection: Set<Long>,
    actions: DownloadListActions,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        DownloadsEmpty(modifier = modifier)
    } else {
        DownloadsList(
            rows = rows,
            actions = actions,
            selection = selection,
            modifier = modifier,
        )
    }
}

@Composable
private fun DownloadsTopBar(
    rows: List<DownloadRow>,
    selection: MutableSet<Long>,
    onBackPressed: () -> Unit,
    onDeleteClick: () -> Unit,
    onRemoveAllClick: () -> Unit,
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
            DownloadsActions(
                rows = rows,
                selection = selection,
                onDeleteClick = onDeleteClick,
                onRemoveAllClick = onRemoveAllClick,
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
private fun DownloadsActions(
    rows: List<DownloadRow>,
    selection: MutableSet<Long>,
    onDeleteClick: () -> Unit,
    onRemoveAllClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row {
        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                )
            }
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = null,
                )
            }
            DownloadsMenu(
                expanded = showMenu,
                rows = rows,
                selection = selection,
                onRemoveAllClick = onRemoveAllClick,
                onDismiss = { showMenu = false },
            )
        }
    }
}

@Composable
private fun DownloadsMenu(
    expanded: Boolean,
    rows: List<DownloadRow>,
    selection: MutableSet<Long>,
    onRemoveAllClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (rows.isNotEmpty() && selection.size < rows.size) {
            DropdownMenuItem(
                onClick = {
                    selection.addAll(rows.map { it.download.id })
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

        DropdownMenuItem(
            onClick = {
                onRemoveAllClick()
                onDismiss()
            },
        ) {
            Text(text = stringResource(R.string.downloads_remove_all))
        }
    }
}
