package com.numenlabs.zerophonev2.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.numenlabs.zerophonev2.ZeroPhoneApp
import com.numenlabs.zerophonev2.data.AppState
import com.numenlabs.zerophonev2.enforcement.ProvisioningStatus
import com.numenlabs.zerophonev2.enforcement.ProvisioningStatusChecker
import com.numenlabs.zerophonev2.ui.AppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val app: ZeroPhoneApp) : ViewModel() {
    data class DistractingApp(
        val packageName: String,
        val label: String,
        val icon: android.graphics.Bitmap?,
        val grantedNow: Boolean,
    )

    data class DashboardUiState(
        val appState: AppState = AppState(),
        val provisioning: ProvisioningStatus? = null,
        val apps: List<DistractingApp> = emptyList(),
        val remainingGrantMillis: Long? = null,
    )

    private val labels = MutableStateFlow<Map<String, DistractingApp>>(emptyMap())

    /** Provisioning/permission snapshot — binder IPC, must stay off the main thread and off the 1 s ticker. */
    private val provisioning: Flow<ProvisioningStatus> =
        flow { emit(ProvisioningStatusChecker.check(app)) }.flowOn(Dispatchers.Default)

    /** 1 s heartbeat so the active-window countdown ticks down. */
    private val ticker: Flow<Long> =
        flow {
            var tick = 0L
            while (true) {
                emit(tick++)
                delay(1_000)
            }
        }

    val uiState: StateFlow<DashboardUiState> =
        combine(
            app.container.repository.state,
            ticker,
            labels,
            provisioning,
        ) { state, _, labelsMap, provisioning ->
            buildUi(state, labelsMap, provisioning)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), DashboardUiState())

    private fun buildUi(
        state: AppState,
        labelsMap: Map<String, DistractingApp>,
        provisioning: ProvisioningStatus,
    ): DashboardUiState {
        val remaining =
            state.activeGrant?.let { grant ->
                val nowEffective = System.currentTimeMillis() + state.clockSkewMillis
                grant.deadlineMillis - nowEffective
            }
        return DashboardUiState(
            appState = state,
            provisioning = provisioning,
            apps =
                state.distractingPackages
                    .map { pkg -> labelsMap[pkg] ?: DistractingApp(pkg, pkg, null, false) }
                    .sortedBy { it.label.lowercase() }
                    .map { it.copy(grantedNow = it.packageName == state.activeGrant?.packageName) },
            remainingGrantMillis = remaining?.takeIf { it > 0 },
        )
    }

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            labels.value =
                AppCatalog.queryLaunchableApps(app).associate { info ->
                    info.packageName to DistractingApp(info.packageName, info.label, info.icon, false)
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DashboardViewModel(app) as T
    }
}
