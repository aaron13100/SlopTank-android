package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R

@Composable
fun DownloadsEmpty(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
) = Box(
    modifier = modifier
        .fillMaxSize()
        .padding(contentPadding),
    contentAlignment = Alignment.Center,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.downloads_empty),
            style = MaterialTheme.typography.h6,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.downloads_empty_description),
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
        )
    }
}
