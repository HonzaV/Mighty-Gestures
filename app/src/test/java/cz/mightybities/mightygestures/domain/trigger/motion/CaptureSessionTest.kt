package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.domain.time.MonotonicClock
import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.Vector3
import cz.mightybities.mightygestures.motion.synthetic.VectorProfile
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.stillness
import cz.mightybities.mightygestures.motion.synthetic.walking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record -> confirm (spec 0001 "Create gesture" steps 2-3, AC-C2-C5), using the real
 * [MotionPipeline], [TemplateValidator] and [MotionMatcher] a live detection pipeline would use
 * (AC-M2).
 */
class CaptureSessionTest {
    private val config = MotionConfig()
    private var clockNanos = 0L
    private val clock = MonotonicClock { clockNanos }

    private fun newSession(hasGyro: Boolean = true) =
        CaptureSession(config, hasGyro, TemplateValidator(config), MotionMatcher(config), Preprocessor(config), clock)

    private fun wrapped(gesture: MotionSegmentSpec) = concat(listOf(stillness(0.6f), gesture, stillness(1.5f)))

    @Test
    fun `recording a clean gesture stores an exemplar that starts at the pre-roll (AC-C2)`() {
        val session = newSession()
        session.startRecording(0L)
        SensorModel()
            .generate(
                wrapped(GesturePrimitives.chop(PerformerVariation.NONE)),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(10),
            ).feedTo(session)
        val result = session.result
        assertTrue(result is CaptureResult.Recorded)
        val exemplar = (result as CaptureResult.Recorded).exemplar
        // The exemplar is rebased so its first timestamp is 0; AC-C2 asks that it also contains
        // the pre-roll, i.e. more than just the active portion starting at onset.
        assertTrue(exemplar.length > 1)
        assertEquals(0L, exemplar.tNanos[0])
    }

    @Test
    fun `no onset within 10s reports NoMovement (AC-C3)`() {
        val session = newSession()
        session.startRecording(0L)
        SensorModel()
            .generate(
                stillness(2f),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(11),
            ).feedTo(session)
        session.checkTimeout(nowNanos = 9_999_000_000L)
        assertEquals(null, session.result)
        session.checkTimeout(nowNanos = 10_000_000_000L)
        assertEquals(CaptureResult.NoMovement, session.result)
    }

    @Test
    fun `timeout is ignored once a movement is in progress`() {
        val session = newSession()
        session.startRecording(0L)
        // Feed up to and including onset, landing the segmenter in ACTIVE, before the deadline check.
        val onsetOnly = concat(listOf(stillness(0.6f), GesturePrimitives.chop(PerformerVariation.NONE)))
        SensorModel().generate(onsetOnly, Quaternion.IDENTITY, hasGyro = true, noise = NoiseSource(12)).feedTo(session)
        // A real device would still be sampling; nothing has produced a result yet, and the
        // deadline (checked on a wall-clock tick) must not cancel an attempt already under way.
        session.checkTimeout(nowNanos = 10_000_000_000L)
        assertEquals(null, session.result)
    }

    @Test
    fun `too gentle a movement is rejected with the validator reason (AC-C4)`() {
        val session = newSession()
        session.startRecording(0L)
        val gentle =
            MotionSegmentSpec(
                durationSeconds = 0.4f,
                linWorld = VectorProfile { Vector3(0f, 4f, 0f) },
                angularBody = VectorProfile { Vector3.ZERO },
            )
        SensorModel()
            .generate(
                wrapped(gentle),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(13),
            ).feedTo(session)
        assertEquals(CaptureResult.Invalid(ValidationFailure.TOO_GENTLE), session.result)
    }

    @Test
    fun `a movement over 3s is rejected as too long (AC-C4)`() {
        val session = newSession()
        session.startRecording(0L)
        val trace = concat(listOf(stillness(0.6f), walking(6f, config = config)))
        SensorModel().generate(trace, Quaternion.IDENTITY, hasGyro = true, noise = NoiseSource(14)).feedTo(session)
        assertEquals(CaptureResult.TooLong, session.result)
    }

    @Test
    fun `confirming a matching repeat succeeds (AC-C5 positive)`() {
        val session = newSession()
        record(session, GesturePrimitives.chop(PerformerVariation.NONE), seed = 20)
        assertTrue(session.result is CaptureResult.Recorded)
        confirm(session, GesturePrimitives.chop(PerformerVariation.sample(NoiseSource(21))), seed = 22)
        assertTrue("expected Confirmed, was ${session.result}", session.result is CaptureResult.Confirmed)
    }

    @Test
    fun `confirming a different movement reports NotMatching (AC-C5 negative)`() {
        val session = newSession()
        record(session, GesturePrimitives.chop(PerformerVariation.NONE), seed = 30)
        assertTrue(session.result is CaptureResult.Recorded)
        confirm(session, GesturePrimitives.twist(PerformerVariation.NONE), seed = 31)
        assertTrue("expected NotMatching, was ${session.result}", session.result is CaptureResult.NotMatching)
    }

    @Test
    fun `the validator does not run during confirmation`() {
        // Regression: a gentle repeat that would fail the record-time validator must still be
        // judged purely by the matcher during confirmation (ADR 0008 "Template validator (record
        // only)"); otherwise a repeat live detection would accept could come back Invalid.
        val session = newSession()
        record(session, GesturePrimitives.chop(PerformerVariation.NONE), seed = 40)
        assertTrue(session.result is CaptureResult.Recorded)
        val gentleRepeat =
            MotionSegmentSpec(
                durationSeconds = 0.2f,
                linWorld = VectorProfile { Vector3(0f, 4f, 0f) },
                angularBody = VectorProfile { Vector3.ZERO },
            )
        session.startConfirming(nowNanos = 5_000_000_000L)
        SensorModel()
            .generate(
                wrapped(gentleRepeat),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(41),
            ).feedTo(session)
        assertTrue(
            "expected NotMatching (not Invalid): the validator must not run on confirm, was ${session.result}",
            session.result is CaptureResult.NotMatching,
        )
    }

    @Test
    fun `ACC-only record and confirm match without a gyroscope (AC-M10)`() {
        val session = newSession(hasGyro = false)
        record(session, GesturePrimitives.shake(PerformerVariation.NONE), seed = 50, hasGyro = false)
        assertTrue(session.result is CaptureResult.Recorded)
        confirm(
            session,
            GesturePrimitives.shake(PerformerVariation.sample(NoiseSource(51))),
            seed = 52,
            hasGyro = false,
        )
        assertTrue("expected Confirmed, was ${session.result}", session.result is CaptureResult.Confirmed)
    }

    private fun record(
        session: CaptureSession,
        gesture: MotionSegmentSpec,
        seed: Long,
        hasGyro: Boolean = true,
    ) {
        session.startRecording(nowNanos = 0L)
        SensorModel()
            .generate(
                wrapped(gesture),
                Quaternion.IDENTITY,
                hasGyro = hasGyro,
                noise = NoiseSource(seed),
            ).feedTo(session)
    }

    private fun confirm(
        session: CaptureSession,
        gesture: MotionSegmentSpec,
        seed: Long,
        hasGyro: Boolean = true,
    ) {
        session.startConfirming(nowNanos = 5_000_000_000L)
        SensorModel()
            .generate(
                wrapped(gesture),
                Quaternion.IDENTITY,
                hasGyro = hasGyro,
                noise = NoiseSource(seed),
            ).feedTo(session)
    }
}
