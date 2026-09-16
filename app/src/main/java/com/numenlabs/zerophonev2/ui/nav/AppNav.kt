package com.numenlabs.zerophonev2.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.numenlabs.zerophonev2.ui.dashboard.DashboardScreen
import com.numenlabs.zerophonev2.ui.provisioning.ProvisioningScreen
import com.numenlabs.zerophonev2.ui.settings.AppPickerScreen
import com.numenlabs.zerophonev2.ui.settings.ColorAppsScreen
import com.numenlabs.zerophonev2.ui.settings.ProtectedAppsScreen
import com.numenlabs.zerophonev2.ui.settings.SettingsScreen

/** Manual navigation over three in-app screens (no nav library needed). */
sealed interface Screen {
    val key: String

    data object Dashboard : Screen {
        override val key: String = "dashboard"
    }

    data object Settings : Screen {
        override val key: String = "settings"
    }

    data object Picker : Screen {
        override val key: String = "picker"
    }

    data object ColorApps : Screen {
        override val key: String = "color_apps"
    }

    data object ProtectedApps : Screen {
        override val key: String = "protected_apps"
    }

    data object Provisioning : Screen {
        override val key: String = "provisioning"
    }
}

@Composable
fun AppNav() {
    // Saveable as a plain string — Screen itself is not Bundle-compatible.
    var currentName by rememberSaveable { mutableStateOf(Screen.Dashboard.key) }
    val current = screenOf(currentName)

    BackHandler(enabled = current != Screen.Dashboard) { currentName = Screen.Dashboard.key }

    when (current) {
        Screen.Dashboard ->
            DashboardScreen(
                onOpenSettings = { currentName = Screen.Settings.key },
                onOpenProvisioning = { currentName = Screen.Provisioning.key },
            )

        Screen.Settings ->
            SettingsScreen(
                onOpenPicker = { currentName = Screen.Picker.key },
                onOpenColorApps = { currentName = Screen.ColorApps.key },
                onOpenProtected = { currentName = Screen.ProtectedApps.key },
            )

        Screen.Picker -> AppPickerScreen(onDone = { currentName = Screen.Settings.key })

        Screen.ColorApps -> ColorAppsScreen(onDone = { currentName = Screen.Settings.key })

        Screen.ProtectedApps -> ProtectedAppsScreen(onDone = { currentName = Screen.Settings.key })

        Screen.Provisioning -> ProvisioningScreen()
    }
}

private fun screenOf(key: String): Screen =
    when (key) {
        Screen.Settings.key -> Screen.Settings
        Screen.Picker.key -> Screen.Picker
        Screen.ColorApps.key -> Screen.ColorApps
        Screen.ProtectedApps.key -> Screen.ProtectedApps
        Screen.Provisioning.key -> Screen.Provisioning
        else -> Screen.Dashboard
    }
