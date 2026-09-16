package com.numenlabs.zerophonev2.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.numenlabs.zerophonev2.ui.AppInfo

private val ActiveGreen = Color(0xFF4CAF50)

/**
 * Grid of distracting apps; a tap starts the gate (or enters directly while
 * the window is open). Deliberately NON-lazy: it lives inside the dashboard's
 * scrollable Column and the distracting set is small.
 */
@Composable
fun AppIconGrid(
    apps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
    activePackage: String? = null,
) {
    val columns = 4
    apps.chunked(columns).forEach { rowApps ->
        Row(modifier = Modifier.fillMaxWidth()) {
            rowApps.forEach { app ->
                val active = app.packageName == activePackage
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (active) {
                                    Modifier
                                        .background(ActiveGreen.copy(alpha = 0.14f))
                                        .border(2.dp, ActiveGreen, RoundedCornerShape(16.dp))
                                } else {
                                    Modifier
                                },
                            ).clickable { onAppClick(app) }
                            .padding(vertical = 8.dp),
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
                        color = if (active) ActiveGreen else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (active) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "● открыто",
                            style = MaterialTheme.typography.labelSmall,
                            color = ActiveGreen,
                        )
                    }
                }
            }
            // Pad the trailing cells so the last row keeps cell widths.
            repeat(columns - rowApps.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
