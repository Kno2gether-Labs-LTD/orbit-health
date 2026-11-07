package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Instant
import java.time.LocalTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private val t0 = Instant.parse("2026-11-04T22:00:00Z")

    private fun session(deep: Int, rem: Int, light: Int, awake: Int): SleepSession {
        var t = t0
        val segs = listOf(SleepStage.DEEP to deep, SleepStage.REM to rem, SleepStage.LIGHT to light, SleepStage.AWAKE to awake)
            .filter { it.second > 0 }
            .map { (st, min) -> SleepSegment(st, t, min * 60).also { t = t.plusSeconds(min * 60L) } }
        return SleepSession(t0, t, segs)
    }

    @Test fun baselineNullUnderThreeDays() {
        assertNull(Baselines.of(listOf(1.0, 2.0)))
        val b = Baselines.of(listOf(2.0, 4.0, 6.0))!!
        assertEquals(4.0, b.mean, 1e-9)
        assertEquals(Math.sqrt(8.0 / 3), b.sd, 1e-9)
        assertEquals(3, b.days)
    }

    @Test fun readinessNullUntilThreeDays() {
        assertNull(Scores.readiness(55.0, Baselines.of(listOf(55.0, 56.0)), 50.0, Baselines.of(listOf(50.0)), null))
        assertNull(Scores.readiness(55.0, null, 50.0, null, null))
    }

    @Test fun readinessUsesZScoresAndExplains() {
        val hr = Baselines.of(listOf(58.0, 60.0, 62.0))!!
        val hrv = Baselines.of(listOf(40.0, 50.0, 60.0))!!
        val r = Scores.readiness(60.0, hr, 50.0, hrv, null)!!
        assertEquals(70, r.value)
        val better = Scores.readiness(55.0, hr, 70.0, hrv, Score(90, emptyList()))!!
        assertTrue(better.value > 80)
        assertTrue(better.reasons.any { it.contains("HRV") && it.contains("above") })
        assertTrue(better.reasons.any { it.contains("Resting HR") && it.contains("below") })
    }

    @Test fun zeroSdGivesNeutralNotNaN() {
        val flat = Baselines.of(listOf(60.0, 60.0, 60.0))!!
        val r = Scores.readiness(70.0, flat, 50.0, flat, null)!!
        assertEquals(70, r.value)
    }

    @Test fun goodRecoveryRaisesStepGoal() {
        val g = SmartGoals.today(Score(80, emptyList()), 8000, 450, LocalTime.of(23, 0), 0).first()
        assertEquals(9200, g.target)
        assertEquals("raised: well recovered", g.why)
    }

    @Test fun lowRecoveryLowersStepGoal() {
        val g = SmartGoals.today(Score(40, emptyList()), 8000, 450, LocalTime.of(23, 0), 1234).first()
        assertEquals(6800, g.target)
        assertEquals("lowered: recovery day", g.why)
        assertEquals(1234, g.progress)
    }

    @Test fun neutralAndUnknownReadinessKeepBase() {
        assertNull(SmartGoals.today(Score(60, emptyList()), 8000, 450, LocalTime.of(23, 0), 0).first().why)
        assertEquals(8000, SmartGoals.today(null, 8000, 450, LocalTime.of(23, 0), 0).first().target)
    }

    @Test fun otherGoals() {
        val gs = SmartGoals.today(null, 8000, 450, LocalTime.of(22, 45), 0, calmBreaks = 1)
        assertEquals(3, gs.size)
        assertEquals(Goal("2 calm breaks", 2, 1, null), gs[1])
        assertEquals(Goal("In bed by 22:45", 0, 0, null), gs[2])
    }

    @Test fun sleepScorePerfectNight() {
        // 450 asleep: 180 deep+REM = 40%, 0 awake
        val s = Scores.sleep(session(deep = 100, rem = 80, light = 270, awake = 0), 450)!!
        assertEquals(100, s.value)
    }

    @Test fun sleepNullWhenNoSession() {
        assertNull(Scores.sleep(null, 450))
    }

    @Test fun sleepReasonsMentionGoal() {
        val s = Scores.sleep(session(60, 60, 312, 0), 450)!!
        assertTrue(s.reasons.any { it.startsWith("Slept 7h 12m of 7h 30m goal") })
    }

    @Test fun activityScore() {
        assertEquals(50, Scores.activity(4000, 8000).value)
        assertEquals(100, Scores.activity(20000, 8000).value)
    }

    @Test fun scoresAlwaysWithin0to100() {
        val rnd = Random(42)
        repeat(2000) {
            val s = Scores.sleep(session(rnd.nextInt(0, 300), rnd.nextInt(0, 300), rnd.nextInt(0, 400), rnd.nextInt(0, 400)), rnd.nextInt(1, 700))
            if (s != null) assertTrue(s.value in 0..100)
            val hr = Baselines.of(List(rnd.nextInt(3, 10)) { rnd.nextDouble(40.0, 90.0) })
            val hrv = Baselines.of(List(rnd.nextInt(3, 10)) { rnd.nextDouble(10.0, 100.0) })
            val r = Scores.readiness(rnd.nextDouble(30.0, 120.0), hr, rnd.nextDouble(0.0, 200.0), hrv, s)
            assertNotNull(r)
            assertTrue(r!!.value in 0..100)
            val a = Scores.activity(rnd.nextInt(0, 60000), rnd.nextInt(1, 20000))
            assertTrue(a.value in 0..100)
        }
    }
}
