package com.numenlabs.zerophonev2.ui.provisioning

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.numenlabs.zerophonev2.R
import com.numenlabs.zerophonev2.enforcement.ProvisioningStatusChecker
import com.numenlabs.zerophonev2.ui.components.StatusRow

/** One-time setup: statuses + copyable adb commands + instructions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisioningScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val status = remember { ProvisioningStatusChecker.check(context) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.provisioning_title)) }) },
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
            StatusRow(stringResource(R.string.status_device_owner), status.isDeviceOwner)
            StatusRow(stringResource(R.string.status_write_secure_settings), status.hasWriteSecureSettings)
            StatusRow(stringResource(R.string.status_exact_alarms), status.canScheduleExactAlarms)
            StatusRow("CAMERA", status.hasCameraPermission)
            StatusRow("POST_NOTIFICATIONS", status.hasNotificationPermission)

            Spacer(Modifier.height(8.dp))

            CopyableCommand(
                label = stringResource(R.string.provisioning_step1),
                command = "adb shell dpm set-device-owner com.numenlabs.zerophonev2/.admin.ZeroDeviceAdminReceiver",
            )
            CopyableCommand(
                label = stringResource(R.string.provisioning_step2),
                command = "adb shell pm grant com.numenlabs.zerophonev2 android.permission.WRITE_SECURE_SETTINGS",
            )
            CopyableCommand(
                label = stringResource(R.string.provisioning_step3),
                command = "adb shell dumpsys deviceidle whitelist +com.numenlabs.zerophonev2",
            )
            CopyableCommand(
                label = stringResource(R.string.provisioning_step4),
                command = "adb shell appops set com.numenlabs.zerophonev2 android:get_usage_stats allow",
            )
            CopyableCommand(
                label = stringResource(R.string.provisioning_step5),
                command = "adb shell cmd notification allow_listener com.numenlabs.zerophonev2/.media.MediaNotificationListener",
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.provisioning_camera_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_unlock_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_unlock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CopyableCommand(
                label = "",
                command = com.numenlabs.zerophonev2.enforcement.AdbDeactivateReceiver.ADB_COMMAND,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CopyableCommand(
    label: String,
    command: String,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                text = command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(command))
                copied = true
            },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(if (copied) R.string.provisioning_copied else R.string.provisioning_copy)) }
    }
}
