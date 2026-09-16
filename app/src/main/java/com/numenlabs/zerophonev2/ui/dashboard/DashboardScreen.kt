package com.numenlabs.zerophonev2.ui.dashboard

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.numenlabs.zerophonev2.R
import com.numenlabs.zerophonev2.ZeroPhoneApp
import com.numenlabs.zerophonev2.gate.GateActivity
import com.numenlabs.zerophonev2.ui.components.AppIconGrid
import com.numenlabs.zerophonev2.ui.components.StatusRow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenProvisioning: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeroPhoneApp
    val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory(app))
    // Lifecycle-aware: the 1 s countdown ticker must not keep firing while
    // the app is backgrounded with a live activity.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val provisioning = uiState.provisioning

    // Reconcile + keep-alive + fresh app labels on every resume of the dashboard.
    DisposableEffect(Unit) {
        viewModel.requestReconcile()
        viewModel.refreshApps()
        onDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                actions = {
                    OutlinedButton(
                        onClick = onOpenProvisioning,
                        modifier = Modifier.padding(end = 4.dp),
                    ) { Text(stringResource(R.string.dashboard_open_provisioning)) }
                    OutlinedButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.dashboard_open_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (provisioning?.isDeviceOwner == true && !provisioning.hasWriteSecureSettings) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.dashboard_wss_missing_banner),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            provisioning?.let { p ->
                StatusRow(stringResource(R.string.status_device_owner), p.isDeviceOwner)
                StatusRow(stringResource(R.string.status_write_secure_settings), p.hasWriteSecureSettings)
                StatusRow(
                    stringResource(R.string.status_grayscale),
                    uiState.appState.grayscaleEnforced && p.hasWriteSecureSettings,
                    stringResource(if (uiState.appState.grayscaleEnforced) R.string.status_on else R.string.status_off),
                )
                StatusRow(
                    stringResource(R.string.status_dns, uiState.appState.dnsHost.ifBlank { "—" }),
                    uiState.appState.dnsAppliedOk,
                )
                StatusRow(stringResource(R.string.status_exact_alarms), p.canScheduleExactAlarms)
            }

            if (uiState.appState.activeGrant?.perSession == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.dashboard_active_session,
                        uiState.apps.firstOrNull { it.grantedNow }?.label ?: "",
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            } else if (uiState.appState.activeGrant?.mediaHold == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.dashboard_active_window,
                        uiState.apps.firstOrNull { it.grantedNow }?.label ?: "",
                        formatDuration(uiState.remainingGrantMillis ?: 0),
                    ) + "  " + stringResource(R.string.dashboard_media_hold),
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                uiState.remainingGrantMillis?.let { remaining ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.dashboard_active_window,
                            uiState.apps.firstOrNull { it.grantedNow }?.label ?: "",
                            formatDuration(remaining),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                } ?: Text(
                    stringResource(R.string.dashboard_no_window),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_distracting_header, uiState.appState.distractingPackages.size),
                style = MaterialTheme.typography.titleMedium,
            )
            if (uiState.apps.isEmpty()) {
                Text(
                    stringResource(R.string.dashboard_distacting_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                Text(
                    stringResource(R.string.dashboard_distacting_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppIconGrid(
                    apps =
                        uiState.apps.map {
                            com.numenlabs.zerophonev2.ui.AppInfo(it.packageName, it.label, it.icon)
                        },
                    onAppClick = { appInfo ->
                        uiState.apps
                            .firstOrNull { it.packageName == appInfo.packageName }
                            ?.let { viewModel.onAppClicked(it) }
                    },
                    activePackage = uiState.appState.activeGrant?.packageName,
                )
            }
            if (uiState.protectedApps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.dashboard_protected_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                AppIconGrid(
                    apps =
                        uiState.protectedApps.map {
                            com.numenlabs.zerophonev2.ui.AppInfo(it.packageName, it.label, it.icon)
                        },
                    onAppClick = { appInfo ->
                        uiState.protectedApps
                            .firstOrNull { it.packageName == appInfo.packageName }
                            ?.let { viewModel.onAppClicked(it) }
                    },
                    activePackage = uiState.appState.activeGrant?.packageName,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
