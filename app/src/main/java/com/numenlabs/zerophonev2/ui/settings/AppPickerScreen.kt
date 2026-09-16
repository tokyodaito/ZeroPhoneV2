package com.numenlabs.zerophonev2.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.numenlabs.zerophonev2.R
import com.numenlabs.zerophonev2.ZeroPhoneApp
import com.numenlabs.zerophonev2.data.PerAppConfig
import com.numenlabs.zerophonev2.enforcement.ForegroundWatcher
import com.numenlabs.zerophonev2.ui.AppInfo

/**
 * Searchable checkbox list over all launchable apps. Selected apps reveal two
 * per-app switches (color / per-session unlock); both require usage access and
 * freeze once the setup is locked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeroPhoneApp
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app))
    val state by viewModel.appState.collectAsState()
    val allApps by viewModel.allApps.collectAsState()

    var search by remember { mutableStateOf("") }
    var selection by remember(state.distractingPackages) { mutableStateOf(state.distractingPackages) }
    val config = remember { mutableStateMapOf<String, PerAppConfig>() }
    state.perAppConfig.forEach { (pkg, cfg) -> if (pkg !in config) config[pkg] = cfg }

    val hasUsageAccess = remember { ForegroundWatcher.hasUsageAccess(context) }
    val canEditToggles = !state.locked && hasUsageAccess

    val filtered =
        remember(allApps, search) {
            if (search.isBlank()) {
                allApps
            } else {
                allApps.filter {
                    it.label.contains(search, ignoreCase = true) ||
                        it.packageName.contains(search, ignoreCase = true)
                }
            }
        }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.picker_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            Text(
                stringResource(R.string.picker_selected_count, selection.size),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.picker_search_hint)) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
            if (!hasUsageAccess) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.picker_usage_banner),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }) { Text(stringResource(R.string.picker_usage_grant)) }
                }
            } else if (!state.locked) {
                Text(
                    stringResource(R.string.picker_toggles_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (state.locked) {
                Text(
                    stringResource(R.string.picker_toggles_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.packageName }) { appInfo: AppInfo ->
                    PickerRow(
                        appInfo = appInfo,
                        selected = appInfo.packageName in selection,
                        cfg = config[appInfo.packageName] ?: PerAppConfig(),
                        canEditToggles = canEditToggles,
                        onToggleSelect = {
                            selection =
                                if (appInfo.packageName in selection) {
                                    config.remove(appInfo.packageName)
                                    selection - appInfo.packageName
                                } else {
                                    config[appInfo.packageName] = config[appInfo.packageName] ?: PerAppConfig()
                                    selection + appInfo.packageName
                                }
                        },
                        onCfgChange = { config[appInfo.packageName] = it },
                    )
                }
            }
            Box(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = {
                        viewModel.setDistracting(selection, config.toMap())
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.picker_done)) }
            }
        }
    }
}

@Composable
private fun PickerRow(
    appInfo: AppInfo,
    selected: Boolean,
    cfg: PerAppConfig,
    canEditToggles: Boolean,
    onToggleSelect: () -> Unit,
    onCfgChange: (PerAppConfig) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleSelect)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (appInfo.icon != null) {
                Image(
                    bitmap = appInfo.icon.asImageBitmap(),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .padding(end = 12.dp)
                            .size(40.dp),
                )
            }
            Text(
                text = appInfo.label,
                modifier = Modifier.weight(1f),
            )
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }
        if (selected) {
            ToggleLine(
                label = stringResource(R.string.picker_toggle_session),
                checked = cfg.perSession,
                enabled = canEditToggles,
            ) { onCfgChange(cfg.copy(perSession = it)) }
        }
    }
}

@Composable
private fun ToggleLine(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
