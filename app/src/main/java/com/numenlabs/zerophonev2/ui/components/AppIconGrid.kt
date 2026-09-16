package com.numenlabs.zerophonev2.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.numenlabs.zerophonev2.ui.AppInfo

/**
 * Grid of distracting apps; a tap starts the 60-second gate. Deliberately
 * NON-lazy: it lives inside the dashboard's scrollable Column and the
 * distracting set is small — a nested LazyVerticalGrid would crash.
 */
@Composable
fun AppIconGrid(
    apps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
) {
    val columns = 4
    apps.chunked(columns).forEach { rowApps ->
        Row(modifier = Modifier.fillMaxWidth()) {
            rowApps.forEach { app ->
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(8.dp)
                            .clickable { onAppClick(app) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (app.icon != null) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.label,
                            modifier =
                                Modifier
                                    .aspectRatio(1f)
                                    .padding(4.dp),
                        )
                    }
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Pad the trailing cells so the last row keeps cell widths.
            repeat(columns - rowApps.size) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
