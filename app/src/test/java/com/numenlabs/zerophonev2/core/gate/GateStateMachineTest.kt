package com.numenlabs.zerophonev2.core.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateStateMachineTest {
    private val duration = 60_000L

    private fun machine() = GateStateMachine(duration)

    @Test
    fun `start moves idle to watching without credit`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        assertEquals(GateState.Watching(0, false), m.state)
    }

    @Test
    fun `ticks without attention add no credit`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.Tick(5_000))
        m.onEvent(GateEvent.Tick(10_000))
        assertEquals(GateState.Watching(0, false), m.state)
    }

    @Test
    fun `ticks with attention accrue credit from first FacePresent`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.Tick(3_000)) // gap before face — not counted
        m.onEvent(GateEvent.FacePresent(3_000))
        m.onEvent(GateEvent.Tick(5_000)) // 2 s of attention
        m.onEvent(GateEvent.Tick(9_000)) // 4 s more
        assertEquals(GateState.Watching(6_000, true), m.state)
    }

    @Test
    fun `face gap within hysteresis keeps credit running`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.FacePresent(0))
        m.onEvent(GateEvent.Tick(10_000))
        // No FaceLost event (blink shorter than hysteresis) — credit continues.
        m.onEvent(GateEvent.Tick(20_000))
        assertEquals(GateState.Watching(20_000, true), m.state)
    }

    @Test
    fun `face lost stops the timer with held snapshot`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.FacePresent(0))
        m.onEvent(GateEvent.Tick(10_000))
        m.onEvent(GateEvent.FaceLost(15_000))
        assertEquals(GateState.Stopped(GateStopReason.FACE_LOST, 10_000), m.state)
    }

    @Test
    fun `focus lost and screen off stop the timer`() {
        val m1 = machine()
        m1.onEvent(GateEvent.Start(0))
        m1.onEvent(GateEvent.FacePresent(0))
        m1.onEvent(GateEvent.Tick(5_000))
        m1.onEvent(GateEvent.FocusLost(6_000))
        assertEquals(GateState.Stopped(GateStopReason.FOCUS_LOST, 5_000), m1.state)

        val m2 = machine()
        m2.onEvent(GateEvent.Start(0))
        m2.onEvent(GateEvent.FacePresent(0))
        m2.onEvent(GateEvent.Tick(5_000))
        m2.onEvent(GateEvent.ScreenOff(6_000))
        assertEquals(GateState.Stopped(GateStopReason.SCREEN_OFF, 5_000), m2.state)
    }

    @Test
    fun `stopped state ignores camera and tick events - no auto resume`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.FacePresent(0))
        m.onEvent(GateEvent.FaceLost(1_000))
        m.onEvent(GateEvent.FacePresent(2_000))
        m.onEvent(GateEvent.Tick(3_000))
        assertEquals(GateState.Stopped(GateStopReason.FACE_LOST, 0), m.state)
    }

    @Test
    fun `resume restarts from zero - never continues remainder`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.FacePresent(0))
        m.onEvent(GateEvent.Tick(50_000)) // almost done
        m.onEvent(GateEvent.FocusLost(51_000))
        m.onEvent(GateEvent.Resume(60_000))
        assertEquals(GateState.Watching(0, false), m.state)
        m.onEvent(GateEvent.FacePresent(60_000))
        m.onEvent(GateEvent.Tick(61_000))
        assertEquals(GateState.Watching(1_000, true), m.state)
    }

    @Test
    fun `camera error stops with reason`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.FacePresent(0))
        m.onEvent(GateEvent.Tick(2_000))
        m.onEvent(GateEvent.CameraError)
        assertEquals(GateState.Stopped(GateStopReason.CAMERA_ERROR, 2_000), m.state)
    }

    @Test
    fun `abandon from watching and stopped`() {
        val m1 = machine()
        m1.onEvent(GateEvent.Start(0))
        m1.onEvent(GateEvent.Abandon)
        assertEquals(GateState.Abandoned, m1.state)

        val m2 = machine()
        m2.onEvent(GateEvent.Start(0))
        m2.onEvent(GateEvent.FaceLost(1))
        m2.onEvent(GateEvent.Abandon)
        assertEquals(GateState.Abandoned, m2.state)
    }

    @Test
    fun `completed exactly at duration and terminal afterwards`() {
        val m = machine()
        m.onEvent(GateEvent.Start(0))
        m.onEvent(GateEvent.FacePresent(0))
        m.onEvent(GateEvent.Tick(59_999))
        assertEquals(GateState.Watching(59_999, true), m.state)
        m.onEvent(GateEvent.Tick(60_000))
        assertEquals(GateState.Completed, m.state)
        m.onEvent(GateEvent.FaceLost(61_000))
        m.onEvent(GateEvent.Resume(62_000))
        assertEquals(GateState.Completed, m.state)
    }

    @Test
    fun `idle ignores everything but start and abandon`() {
        val m = machine()
        m.onEvent(GateEvent.Tick(1))
        m.onEvent(GateEvent.FacePresent(2))
        assertEquals(GateState.Idle, m.state)
        // Abandon works BEFORE the countdown starts (preview phase).
        m.onEvent(GateEvent.Abandon)
        assertEquals(GateState.Abandoned, m.state)
    }
}
