package com.numenlabs.zerophonev2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.numenlabs.zerophonev2.ZeroPhoneApp
import com.numenlabs.zerophonev2.data.AppState
import com.numenlabs.zerophonev2.enforcement.EnforcementService
import com.numenlabs.zerophonev2.ui.AppCatalog
import com.numenlabs.zerophonev2.ui.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: ZeroPhoneApp) : ViewModel() {
    val appState: StateFlow<AppState> =
        app.container.repository.state
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), AppState())

    val allApps: StateFlow<List<AppInfo>> =
        kotlinx.coroutines.flow
            .flow { emit(AppCatalog.queryLaunchableApps(app)) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _deactivated = MutableStateFlow(false)
    val deactivated: StateFlow<Boolean> = _deactivated.asStateFlow()

    fun setDistracting(
        packages: Set<String>,
        perAppConfig: Map<String, com.numenlabs.zerophonev2.data.PerAppConfig> = emptyMap(),
    ) {
        viewModelScope.launch {
            app.container.repository.update {
                it.copy(
                    distractingPackages = packages,
                    // Keep configs only for selected apps; freeze semantics after lock are UI-enforced.
                    perAppConfig = perAppConfig.filterKeys { pkg -> pkg in packages },
                )
            }
            requestReconcile()
        }
    }

    fun setColorApps(packages: Set<String>) {
        viewModelScope.launch {
            app.container.repository.update { it.copy(colorPackages = packages) }
            // Entering/leaving watch mode + immediate grayscale target refresh.
            app.container.engine.requestGrayscaleReassert()
        }
    }

    fun setProtectedApps(packages: Set<String>) {
        viewModelScope.launch {
            app.container.repository.update { it.copy(protectedPackages = packages) }
            requestReconcile()
        }
    }

    fun setGrayscale(enabled: Boolean) {
        viewModelScope.launch {
            app.container.repository.update { it.copy(grayscaleEnforced = enabled) }
            if (!enabled) {
                if (app.container.grayscaleController.hasWritePermission()) {
                    val state = app.container.repository.snapshot()
                    app.container.grayscaleController.apply(
                        com.numenlabs.zerophonev2.core.policy.GrayscalePolicy.restoreValues(
                            state.grayscaleOriginalEnabled,
                            state.grayscaleOriginalMode,
                        ),
                    )
                }
                EnforcementService.ensureStopped(app)
            } else {
                EnforcementService.ensureStarted(app)
            }
            requestReconcile()
        }
    }

    fun applyDns(host: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                app.container.repository.update {
                    it.copy(dnsHost = host.trim(), dnsEnforced = host.isNotBlank(), dnsAppliedOk = false, dnsLastError = "")
                }
                // Await the real work (5 s TLS probe) before clearing the busy flag.
                app.container.engine.reconcile()
            } catch (_: Exception) {
            } finally {
                _busy.value = false
            }
        }
    }

    fun turnOffDns() {
        viewModelScope.launch {
            _busy.value = true
            try {
                app.container.repository.update {
                    it.copy(dnsHost = "", dnsEnforced = false, dnsAppliedOk = false, dnsLastError = "")
                }
                app.container.dnsController.undo()
                app.container.engine.reconcile()
            } catch (_: Exception) {
            } finally {
                _busy.value = false
            }
        }
    }

    /** «Сохранить и заблокировать настройки» — after this, phone-side off-switches vanish. */
    fun lockSetup() {
        viewModelScope.launch {
            app.container.repository.update { it.copy(locked = true) }
            EnforcementService.ensureStarted(app)
        }
    }

    fun setPauseTimerOnMedia(enabled: Boolean) {
        viewModelScope.launch {
            app.container.repository.update { it.copy(pauseTimerOnMedia = enabled) }
            EnforcementService.ensureStarted(app)
        }
    }

    fun deactivate() {
        viewModelScope.launch {
            _busy.value = true
            try {
                EnforcementService.ensureStopped(app)
                app.container.engine.deactivate()
                _deactivated.value = true
            } finally {
                _busy.value = false
            }
        }
    }

    fun requestReconcile() {
        app.container.engine.scope.launch {
            try {
                app.container.engine.reconcile()
            } catch (_: Exception) {
            }
        }
    }

    class Factory(private val app: ZeroPhoneApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(app) as T
    }
}
