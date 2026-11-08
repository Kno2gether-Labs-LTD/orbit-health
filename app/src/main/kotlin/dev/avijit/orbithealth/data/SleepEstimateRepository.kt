package dev.avijit.orbithealth.data

import androidx.room.withTransaction
import dev.avijit.orbithealth.insights.EstimatePlan
import dev.avijit.orbithealth.insights.EstimateStatus
import dev.avijit.orbithealth.insights.SleepEstimate
import dev.avijit.orbithealth.insights.SleepEstimates
import dev.avijit.orbithealth.insights.SleepEstimator
import dev.avijit.orbithealth.insights.SleepKind
import dev.avijit.orbithealth.insights.SleepTuning
import dev.avijit.orbithealth.ring.Metric
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Estimated sleep: detection over the newest data, and the user's answers. Lives in the encrypted database next to the
 * ring's own nights, in its own table, so the server push (which reads only the ring's tables) never sends it.
 */
class SleepEstimateRepository(
    private val db: OrbitDatabase,
    private val samples: SampleRepository,
    private val clock: Clock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.sleepEstimateDao()
    private val mutex = Mutex()

    /** Estimates (any status) ending in [from]..[to], oldest first. */
    fun estimates(from: Instant, to: Instant): Flow<List<SleepEstimate>> =
        dao.between(from.toEpochMilli(), to.toEpochMilli()).map { l -> l.map { it.toDomain() } }

    /** The tuning learned from every answer so far. */
    suspend fun tuning(): SleepTuning = SleepTuning.replay(dao.decided().mapNotNull { it.toDomain().decision() })

    /**
     * Runs the detector over the last [SCAN] and stores what changed (see [SleepEstimates.plan]). Serialised and
     * idempotent: safe to call after every sync and whenever a screen opens.
     */
    suspend fun refresh(): EstimatePlan = mutex.withLock {
        val now = clock.instant()
        val z = zone()
        val scanFrom = now.minus(SCAN)
        // The detector looks a day further back so a night crossing the scan's start is seen whole.
        val detectFrom = scanFrom.minus(Duration.ofDays(1))
        val baselineFrom = now.minus(BASELINE)
        val hr = samples.samples(Metric.HEART_RATE, baselineFrom, now).first()
        val steps = samples.samples(Metric.STEPS, detectFrom, now).first()
        val spo2 = samples.samples(Metric.SPO2, detectFrom, now).first()
        val sessions = samples.sleepSessions(baselineFrom.minus(Duration.ofHours(18)), now).first()
        val params = tuning().params()
        val detected = SleepEstimator.detect(hr, steps, spo2, sessions, detectFrom, now, z, params).filter { it.end > scanFrom }
        val stored = dao.overlapping(detectFrom.toEpochMilli(), now.toEpochMilli()).map { it.toDomain() }
        val plan = SleepEstimates.plan(stored, detected, sessions, scanFrom, now)
        if (!plan.isEmpty) db.withTransaction {
            if (plan.delete.isNotEmpty()) dao.deleteSuggestions(plan.delete)
            plan.update.forEach { (id, d) ->
                dao.refreshSuggestion(id, d.kind.name, d.start.toEpochMilli(), d.end.toEpochMilli(), d.lowestHr, d.lowestSpo2, d.confidence)
            }
            plan.insert.forEach { d ->
                dao.insert(
                    SleepEstimateEntity(
                        kind = d.kind.name, status = EstimateStatus.SUGGESTED.name,
                        startMs = d.start.toEpochMilli(), endMs = d.end.toEpochMilli(),
                        detectedStartMs = d.start.toEpochMilli(), detectedEndMs = d.end.toEpochMilli(),
                        lowestHr = d.lowestHr, lowestSpo2 = d.lowestSpo2, confidence = d.confidence,
                        createdAtMs = now.toEpochMilli(), decidedAtMs = null,
                    ),
                )
            }
        }
        plan
    }

    /**
     * Keeps suggestion [id], at [start]..[end] when the user adjusted it (else as suggested). False when it was already
     * answered or is gone. Times are kept in order and at least a minute apart.
     */
    suspend fun confirm(id: Long, start: Instant? = null, end: Instant? = null): Boolean = mutex.withLock {
        val e = dao.byId(id) ?: return@withLock false
        val s = start?.toEpochMilli() ?: e.startMs
        val en = (end?.toEpochMilli() ?: e.endMs).coerceAtLeast(s + 60_000)
        dao.confirm(id, s, en, clock.millis()) > 0
    }

    /** Turns suggestion [id] down for good. False when it was already answered or is gone. */
    suspend fun dismiss(id: Long): Boolean = mutex.withLock { dao.dismiss(id, clock.millis()) > 0 }

    companion object {
        /** How far back detection looks for missed sleep. */
        val SCAN: Duration = Duration.ofDays(3)

        /** Heart rate read for the awake baseline. */
        val BASELINE: Duration = Duration.ofDays(8)
    }
}

internal fun SleepEstimateEntity.toDomain() = SleepEstimate(
    id = id,
    kind = runCatching { SleepKind.valueOf(kind) }.getOrDefault(SleepKind.NIGHT),
    status = runCatching { EstimateStatus.valueOf(status) }.getOrDefault(EstimateStatus.DISMISSED),
    start = Instant.ofEpochMilli(startMs),
    end = Instant.ofEpochMilli(endMs),
    detectedStart = Instant.ofEpochMilli(detectedStartMs),
    detectedEnd = Instant.ofEpochMilli(detectedEndMs),
    lowestHr = lowestHr,
    lowestSpo2 = lowestSpo2,
    confidence = confidence,
    decidedAt = decidedAtMs?.let(Instant::ofEpochMilli),
)
