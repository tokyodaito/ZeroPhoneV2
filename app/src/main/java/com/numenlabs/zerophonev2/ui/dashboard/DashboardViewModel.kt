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
        val protectedApps: List<DistractingApp> = emptyList(),
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
        fun resolve(pkg: String): DistractingApp =
            labelsMap[pkg] ?: DistractingApp(pkg, state.appLabels[pkg] ?: pkg, null, false)

        return DashboardUiState(
            appState = state,
            provisioning = provisioning,
            apps =
                state.distractingPackages
                    .map { pkg -> resolve(pkg) }
                    .sortedBy { it.label.lowercase() }
                    .map { it.copy(grantedNow = it.packageName == state.activeGrant?.packageName) },
            protectedApps =
                state.protectedPackages
                    .map { pkg -> resolve(pkg) }
                    .sortedBy { it.label.lowercase() }
                    .map { it.copy(grantedNow = it.packageName == state.activeGrant?.packageName) },
            remainingGrantMillis = remaining?.takeIf { it > 0 },
        )
    }

    init {
        seedFromPersistentCache()
        refreshApps()
    }

    /** Instant cold-start content: persisted labels + cached icon PNGs (fast disk read). */
    private fun seedFromPersistentCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val state = app.container.repository.snapshot()
                val seeded =
                    (state.distractingPackages + state.protectedPackages).mapNotNull { pkg ->
                        val label = state.appLabels[pkg] ?: return@mapNotNull null
                        com.numenlabs.zerophonev2.data.IconCache.load(app, pkg)?.let { icon ->
                            pkg to DistractingApp(pkg, label, icon, false)
                        }
                    }.toMap()
                if (seeded.isNotEmpty()) labels.value = seeded
            } catch (_: Exception) {
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            val catalog = AppCatalog.queryLaunchableApps(app)
            labels.value =
                catalog.associate { info ->
                    info.packageName to DistractingApp(info.packageName, info.label, info.icon, false)
                }
            // Persist labels + icons of distracting apps so a cold start never
            // flashes raw package names / empty tiles while the query runs.
            try {
                val state = app.container.repository.snapshot()
                val wanted = state.distractingPackages + state.protectedPackages
                if (wanted.isNotEmpty()) {
                    val fresh = catalog.filter { it.packageName in wanted }.map { it.packageName to it.label }
                    if (fresh.toMap() != state.appLabels.filterKeys { it in wanted }) {
                        app.container.repository.update { it.copy(appLabels = fresh.toMap()) }
                    }
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        catalog.filter { it.packageName in wanted && it.icon != null }.forEach {
                            com.numenlabs.zerophonev2.data.IconCache.save(app, it.packageName, it.icon!!)
                        }
                        com.numenlabs.zerophonev2.data.IconCache.prune(app, wanted)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Tap in the dashboard grid: an active window for this app → straight in, otherwise the gate. */
    fun onAppClicked(target: DistractingApp) {
        viewModelScope.launch {
            val state = app.container.repository.snapshot()
            val grant = state.activeGrant
            val windowOpen =
                grant != null && grant.packageName == target.packageName &&
                    (grant.perSession ||
                        com.numenlabs.zerophonev2.core.ledger.GrantLedger.remainingMillis(
                            grant,
                            System.currentTimeMillis() + state.clockSkewMillis,
                        ) != null)
            if (windowOpen) {
                try {
                    app.packageManager.getLaunchIntentForPackage(target.packageName)?.let {
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(it)
                    }
                } catch (_: Exception) {
                }
            } else if (target.packageName in state.protectedPackages) {
                com.numenlabs.zerophonev2.gate.BiometricGateActivity.start(app, target.packageName)
            } else {
                com.numenlabs.zerophonev2.gate.GateActivity.start(app, target.packageName)
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
