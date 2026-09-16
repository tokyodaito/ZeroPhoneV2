package com.numenlabs.zerophonev2.ui.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.numenlabs.zerophonev2.R
import com.numenlabs.zerophonev2.ZeroPhoneApp
import com.numenlabs.zerophonev2.enforcement.AdbDeactivateReceiver
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenPicker: () -> Unit,
    onOpenColorApps: () -> Unit,
    onOpenProtected: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeroPhoneApp
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app))
    val state by viewModel.appState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val deactivated by viewModel.deactivated.collectAsState()

    var dnsHost by remember(state.dnsHost) { mutableStateOf(state.dnsHost) }
    var confirmDeactivate by remember { mutableStateOf(false) }
    var confirmLock by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableStateOf(0f) }
    val hasNotificationAccess =
        remember { com.numenlabs.zerophonev2.media.MediaWatcher.hasNotificationAccess(context) }

    LaunchedEffect(holding) {
        if (holding) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < HOLD_MILLIS) {
                holdProgress = (System.currentTimeMillis() - start) / HOLD_MILLIS.toFloat()
                delay(50)
            }
            holdProgress = 0f
            holding = false
            confirmDeactivate = true
        } else {
            holdProgress = 0f // Early release — drop the partial error tint.
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.locked) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.settings_locked_banner),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // --- Distracting apps (editable in every mode — it is day-to-day config) ---
            Text(
                stringResource(R.string.settings_distracting_header, state.distractingPackages.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_distracting_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenPicker, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_pick_apps))
            }
            OutlinedButton(onClick = onOpenColorApps, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_color_apps, state.colorPackages.size))
            }
            OutlinedButton(onClick = onOpenProtected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_protected_apps, state.protectedPackages.size))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // --- Grayscale ---
            Text(stringResource(R.string.settings_grayscale_header), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (state.locked) R.string.settings_grayscale_locked_hint else R.string.settings_grayscale_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.locked) {
                Text(
                    stringResource(
                        if (state.grayscaleEnforced) R.string.status_on else R.string.status_off,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                SwitchRow(checked = state.grayscaleEnforced, onCheckedChange = viewModel::setGrayscale)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // --- Media hold (don't fire the 5-min timer mid-video) ---
            Text(
                stringResource(R.string.settings_media_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_media_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.locked) {
                Text(
                    stringResource(
                        if (state.pauseTimerOnMedia) R.string.status_on else R.string.status_off,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                SwitchRow(
                    checked = state.pauseTimerOnMedia,
                    onCheckedChange = viewModel::setPauseTimerOnMedia,
                )
            }
            if (!hasNotificationAccess) {
                Text(
                    stringResource(R.string.settings_media_access_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    } catch (_: Exception) {
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_media_grant))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // --- DNS ---
            Text(stringResource(R.string.settings_dns_header), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_dns_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = dnsHost,
                onValueChange = { dnsHost = it },
                label = { Text(stringResource(R.string.settings_dns_host_hint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.dnsLastError.isNotBlank()) {
                Text(
                    stringResource(R.string.settings_dns_last_error, state.dnsLastError),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.dnsAppliedOk) {
                Text(
                    stringResource(R.string.settings_dns_applied_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = { viewModel.applyDns(dnsHost) },
                enabled = !busy && dnsHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (busy) R.string.settings_dns_applying else R.string.settings_dns_apply))
            }
            // Turning DNS OFF is a phone-side off-switch: hidden once locked.
            if (!state.locked && (state.dnsEnforced || state.dnsAppliedOk)) {
                OutlinedButton(
                    onClick = viewModel::turnOffDns,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_dns_turn_off)) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (!state.locked) {
                // --- Lock the setup (the "save" of the initial configuration) ---
                Text(
                    stringResource(R.string.settings_lock_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.settings_lock_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { confirmLock = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) { Text(stringResource(R.string.settings_lock_button)) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // --- Free deactivation (only BEFORE the setup is locked) ---
                Text(
                    stringResource(R.string.settings_deactivate_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_deactivate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {},
                    enabled = !busy && !deactivated,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        holding = true
                                        try {
                                            awaitRelease()
                                        } finally {
                                            holding = false
                                        }
                                    },
                                )
                            },
                    colors =
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor =
                                lerp(
                                    MaterialTheme.colorScheme.error,
                                    MaterialTheme.colorScheme.errorContainer,
                                    holdProgress,
                                ),
                        ),
                ) {
                    Text(
                        stringResource(
                            if (deactivated) R.string.settings_deactivate_done_short
                            else R.string.settings_deactivate_button,
                        ),
                    )
                }
                if (deactivated) {
                    Text(
                        stringResource(R.string.settings_deactivate_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // --- Locked: the only guide out — a computer with adb ---
                Text(
                    stringResource(R.string.settings_unlock_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_unlock_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UnlockCommand()
                Text(
                    stringResource(R.string.settings_unlock_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDeactivate) {
        AlertDialog(
            onDismissRequest = { confirmDeactivate = false },
            title = { Text(stringResource(R.string.settings_deactivate_confirm_title)) },
            text = { Text(stringResource(R.string.settings_deactivate_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeactivate = false
                        viewModel.deactivate()
                    },
                ) { Text(stringResource(R.string.settings_deactivate_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeactivate = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmLock) {
        AlertDialog(
            onDismissRequest = { confirmLock = false },
            title = { Text(stringResource(R.string.settings_lock_confirm_title)) },
            text = { Text(stringResource(R.string.settings_lock_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLock = false
                        viewModel.lockSetup()
                    },
                ) { Text(stringResource(R.string.settings_lock_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLock = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Copyable adb command that reverses everything once locked. */
@Composable
private fun UnlockCommand() {
    var copied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = AdbDeactivateReceiver.ADB_COMMAND,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }
        OutlinedButton(
            onClick = {
                clipboard.setText(
                    androidx.compose.ui.text.AnnotatedString(AdbDeactivateReceiver.ADB_COMMAND),
                )
                copied = true
            },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(if (copied) R.string.provisioning_copied else R.string.provisioning_copy)) }
    }
}

@Composable
private fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private const val HOLD_MILLIS = 3_000L
