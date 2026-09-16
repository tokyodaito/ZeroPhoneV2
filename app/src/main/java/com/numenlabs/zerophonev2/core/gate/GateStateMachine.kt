package com.numenlabs.zerophonev2.core.gate

/**
 * Pure finite-state machine for the 60-second attention gate.
 *
 * The countdown credits time ONLY while attention is held (face with open
 * eyes detected + the gate screen focused + screen on). Any distraction
 * moves the machine to [GateState.Stopped] — the timer freezes and restarts
 * from zero ONLY when the user taps «Попробовать снова» ([GateEvent.Resume]).
 * Abandoning («Не сейчас») is always allowed.
 *
 * All event timestamps are monotonic (SystemClock.elapsedRealtime).
 */
enum class GateStopReason { FACE_LOST, FOCUS_LOST, SCREEN_OFF, CAMERA_ERROR }

sealed interface GateState {
    data object Idle : GateState

    /** [heldMillis] — accumulated attention credit; [attentionHeld] — face currently confirmed. */
    data class Watching(
        val heldMillis: Long,
        val attentionHeld: Boolean,
    ) : GateState

    data class Stopped(
        val reason: GateStopReason,
        val heldMillisAtStop: Long,
    ) : GateState

    data object Completed : GateState

    data object Abandoned : GateState
}

sealed interface GateEvent {
    data class Start(val nowElapsed: Long) : GateEvent

    data class FacePresent(val nowElapsed: Long) : GateEvent

    data class FaceLost(val nowElapsed: Long) : GateEvent

    /** Window focus lost (incl. notification shade), onStop, PiP, keyguard, split-screen focus loss. */
    data class FocusLost(val nowElapsed: Long) : GateEvent

    data class ScreenOff(val nowElapsed: Long) : GateEvent

    /** Camera failed / permission denied — the gate cannot run. */
    data object CameraError : GateEvent

    /** User tapped «Попробовать снова» — fresh pass from zero. */
    data class Resume(val nowElapsed: Long) : GateEvent

    data class Tick(val nowElapsed: Long) : GateEvent

    /** User tapped «Не сейчас». */
    data object Abandon : GateEvent
}

class GateStateMachine(private val durationMillis: Long) {
    var state: GateState = GateState.Idle
        private set

    /** Elapsed timestamp the running credit is measured from. */
    private var anchorElapsed: Long = 0L

    fun onEvent(event: GateEvent): GateState {
        state = transition(event)
        return state
    }

    private fun transition(event: GateEvent): GateState = when (state) {
        is GateState.Idle -> when (event) {
            is GateEvent.Start -> {
                anchorElapsed = event.nowElapsed
                GateState.Watching(heldMillis = 0L, attentionHeld = false)
            }

            else -> state
        }

        is GateState.Watching -> watching(event, state as GateState.Watching)

        is GateState.Stopped -> when (event) {
            // Only an explicit user action can restart a stopped run — no auto-resume.
            is GateEvent.Resume -> {
                anchorElapsed = event.nowElapsed
                GateState.Watching(heldMillis = 0L, attentionHeld = false)
            }

            is GateEvent.Abandon -> GateState.Abandoned

            else -> state
        }

        GateState.Completed, GateState.Abandoned -> state
    }

    private fun watching(event: GateEvent, current: GateState.Watching): GateState = when (event) {
        is GateEvent.FacePresent ->
            if (current.attentionHeld) {
                current // Credit continues — no re-anchor.
            } else {
                anchorElapsed = event.nowElapsed
                current.copy(attentionHeld = true)
            }

        is GateEvent.FaceLost -> GateState.Stopped(GateStopReason.FACE_LOST, current.heldMillis)

        is GateEvent.FocusLost -> GateState.Stopped(GateStopReason.FOCUS_LOST, current.heldMillis)

        is GateEvent.ScreenOff -> GateState.Stopped(GateStopReason.SCREEN_OFF, current.heldMillis)

        GateEvent.CameraError -> GateState.Stopped(GateStopReason.CAMERA_ERROR, current.heldMillis)

        is GateEvent.Tick -> {
            val held =
                if (current.attentionHeld) {
                    current.heldMillis + (event.nowElapsed - anchorElapsed)
                } else {
                    current.heldMillis
                }
            anchorElapsed = event.nowElapsed
            if (held >= durationMillis) GateState.Completed else GateState.Watching(held, current.attentionHeld)
        }

        is GateEvent.Resume -> current // Resume is only meaningful in Stopped.

        is GateEvent.Abandon -> GateState.Abandoned

        is GateEvent.Start -> current
    }
}
