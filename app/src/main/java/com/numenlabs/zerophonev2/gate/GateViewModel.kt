package com.numenlabs.zerophonev2.gate

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.numenlabs.zerophonev2.ZeroPhoneApp
import com.numenlabs.zerophonev2.core.gate.AttentionPolicy
import com.numenlabs.zerophonev2.core.gate.GateEvent
import com.numenlabs.zerophonev2.core.gate.GateState
import com.numenlabs.zerophonev2.core.gate.GateStateMachine
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bridges Android signals (camera frames, focus/screen-off callbacks) into
 * the pure [GateStateMachine] and exposes UI state. On completion it opens
 * the 5-minute grant and emits [GateVmEvent.LaunchTarget] so the activity
 * can start the target app.
 */
class GateViewModel(
    private val app: ZeroPhoneApp,
    val targetPackage: String,
) : ViewModel() {
    private val events = Channel<GateEvent>(Channel.UNLIMITED)

    private val _uiState = MutableStateFlow<GateState>(GateState.Idle)
    val uiState: StateFlow<GateState> = _uiState.asStateFlow()

    private val _totalSeconds = MutableStateFlow(60)
    val totalSeconds: StateFlow<Int> = _totalSeconds.asStateFlow()

    private val _vmEvents = MutableSharedFlow<GateVmEvent>(extraBufferCapacity = 4)
    val vmEvents: SharedFlow<GateVmEvent> = _vmEvents.asSharedFlow()

    private var gateDurationMillis: Long = 60_000L
    private var stateMachine = GateStateMachine(gateDurationMillis)
    private val attention = AttentionPolicy()
    private var tickerJob: Job? = null

    init {
        send(GateEvent.Start(SystemClock.elapsedRealtime()))
        startTicker()
        viewModelScope.launch {
            // Display-only: the FSM is built with the default 60 s. A custom
            // gateDurationMillis would need loading BEFORE Start (DataStore read
            // is async); not wired to any UI today — revisit if it ever becomes
            // user-configurable.
            gateDurationMillis = app.container.repository.snapshot().gateDurationMillis
            _totalSeconds.value = (gateDurationMillis / 1000L).toInt().coerceAtLeast(1)
        }
        viewModelScope.launch {
            events.consumeAsFlow().collect { event ->
                val previous = stateMachine.state
                val next = stateMachine.onEvent(event)
                _uiState.value = next
                if (next is GateState.Completed && previous !is GateState.Completed) {
                    stopTicker()
                    viewModelScope.launch {
                        val grant = app.container.engine.openGrant(targetPackage)
                        if (grant != null) {
                            _vmEvents.emit(GateVmEvent.LaunchTarget(targetPackage))
                        } else {
                            _vmEvents.emit(GateVmEvent.OpenGrantFailed)
                        }
                    }
                }
                if (next is GateState.Stopped) {
                    stopTicker()
                }
                if (next is GateState.Abandoned && previous !is GateState.Abandoned) {
                    stopTicker()
                    app.container.engine.recordAbandon()
                }
            }
        }
    }

    /** From FaceAnalyzer — must survive configuration/recreation churn. */
    fun onFaceFrame(looking: Boolean) {
        val event =
            attention.onFrame(SystemClock.elapsedRealtime(), looking)?.let { policyEvent ->
                when (policyEvent) {
                    AttentionPolicy.Event.Present -> GateEvent.FacePresent(SystemClock.elapsedRealtime())
                    AttentionPolicy.Event.Lost -> GateEvent.FaceLost(SystemClock.elapsedRealtime())
                }
            }
        event?.let { send(it) }
    }

    fun onCameraError() {
        send(GateEvent.CameraError)
    }

    fun focusLost() = send(GateEvent.FocusLost(SystemClock.elapsedRealtime()))

    fun screenOff() = send(GateEvent.ScreenOff(SystemClock.elapsedRealtime()))

    /** «Попробовать снова» — fresh pass from zero. */
    fun retry() {
        // Only meaningful in Stopped — calling it mid-watch would desync the hysteresis.
        if (_uiState.value !is GateState.Stopped) return
        attention.reset()
        send(GateEvent.Resume(SystemClock.elapsedRealtime()))
        startTicker()
    }

    /** «Не сейчас». */
    fun abandon() = send(GateEvent.Abandon)

    /** Launch failed after grant opened — close the unusable window. */
    fun requestGrantRevoke() {
        app.container.engine.scope.launch {
            try {
                app.container.engine.revokeActiveGrant()
            } catch (_: Exception) {
            }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(TICK_INTERVAL_MILLIS)
                    send(GateEvent.Tick(SystemClock.elapsedRealtime()))
                }
            }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun send(event: GateEvent) {
        events.trySend(event)
    }

    override fun onCleared() {
        stopTicker()
        super.onCleared()
    }

    sealed interface GateVmEvent {
        data class LaunchTarget(val packageName: String) : GateVmEvent

        data object OpenGrantFailed : GateVmEvent
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 200L
    }

    class Factory(
        private val app: ZeroPhoneApp,
        private val targetPackage: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GateViewModel(app, targetPackage) as T
    }
}
