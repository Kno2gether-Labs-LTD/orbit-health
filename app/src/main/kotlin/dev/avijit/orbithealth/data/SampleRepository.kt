package dev.avijit.orbithealth.data

import androidx.room.withTransaction
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.WorkoutRecord
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SampleRepository(private val db: OrbitDatabase) {
    private val samples = db.sampleDao()
    private val sleep = db.sleepDao()
    private val workouts = db.workoutDao()

    /**
     * All-or-nothing (one transaction): the sync engine clears the ring only after this returns.
     *
     * Conflict rules for a row that already exists:
     * - [MAX_WINS] metrics (30-min activity buckets and the day's running step total) keep the larger
     *   value, because the ring rewrites the current bucket as the user keeps moving;
     * - every other metric keeps the first-written value;
     * - a sleep session (keyed by start) keeps the later end, since the ring may report a provisional end;
     *   segments are keyed by (session start, segment start) and only new ones are added.
     *
     * A [SampleSource.HISTORY] sample that is the ring's own copy of an on-demand reading (a [SampleSource.MEASURED]
     * sample of the same metric and rounded value within [MEASURED_COPY_WINDOW_MS]) is dropped: see [dropMeasuredCopies].
     */
    suspend fun store(batch: HistoryBatch) {
        db.withTransaction {
            insertSamples(dropMeasuredCopies(batch.samples))
            val sessions = batch.sleep.map { SleepSessionEntity(it.start.toEpochMilli(), it.end.toEpochMilli()) }
            sleep.insertSessions(sessions).forEachIndexed { i, rowId ->
                if (rowId == -1L) sleep.extendEnd(sessions[i].startMs, sessions[i].endMs)
            }
            sleep.insertSegments(batch.sleep.flatMap { s ->
                s.segments.map {
                    SleepSegmentEntity(
                        sessionStartMs = s.start.toEpochMilli(), stage = it.stage.name,
                        startMs = it.start.toEpochMilli(), durationSec = it.durationSec,
                    )
                }
            })
            workouts.insertAll(batch.workouts.map {
                WorkoutEntity(
                    it.start.toEpochMilli(), it.end.toEpochMilli(), it.steps, it.distanceM, it.kcal, it.mode, it.avgHr,
                )
            })
        }
    }

    suspend fun storeLive(s: Sample) {
        db.withTransaction { insertSamples(listOf(s)) }
    }

    /**
     * Some rings (the TK5, observed 2026-11-06: heart rate and HRV, not SpO2) also write an on-demand reading into their
     * own history, a few seconds from when the app stored the result. Read back by the next sync, that copy would be a
     * second row (and a second server reading, the keys being (metric, time)). Family-agnostic: a HISTORY sample is
     * dropped when a MEASURED sample of the same metric with the same rounded value ([dedupKey]) lies within
     * ±[MEASURED_COPY_WINDOW_MS], already stored or in this batch. The MEASURED row is kept: it is what the user saw and
     * may already be pushed. Other samples (e.g. the step total from the same ring record) are untouched. One query per
     * batch, over the batch's time range.
     *
     * The reverse order (the history copy stored before the MEASURED result) is not handled: both rows would be kept.
     * It cannot happen through [dev.avijit.orbithealth.sync.SyncEngine], which holds its lock from the measurement
     * request until the result is stored, so a sync can only read the ring's copy afterwards; and deleting an existing
     * HISTORY row would be unsafe since the repository cannot tell whether a server push already sent it.
     */
    private suspend fun dropMeasuredCopies(items: List<Sample>): List<Sample> {
        val history = items.filter { it.source == SampleSource.HISTORY }
        if (history.isEmpty()) return items
        val metrics = history.map { it.metric.name }.distinct()
        val from = history.minOf { it.time.toEpochMilli() } - MEASURED_COPY_WINDOW_MS
        val to = history.maxOf { it.time.toEpochMilli() } + MEASURED_COPY_WINDOW_MS
        val measured = samples.bySourceBetween(metrics, SampleSource.MEASURED.name, from, to).map { it.toDomain() } +
            items.filter { it.source == SampleSource.MEASURED }
        if (measured.isEmpty()) return items
        val byKey = measured.groupBy({ it.metric to dedupKey(it.metric, it.value) }, { it.time.toEpochMilli() })
        return items.filter { s ->
            if (s.source != SampleSource.HISTORY) return@filter true
            val t = s.time.toEpochMilli()
            byKey[s.metric to dedupKey(s.metric, s.value)].orEmpty().none { abs(it - t) <= MEASURED_COPY_WINDOW_MS }
        }
    }

    private suspend fun insertSamples(items: List<Sample>) {
        val entities = items.map { it.toEntity() }
        samples.insertAll(entities).forEachIndexed { i, rowId ->
            val e = entities[i]
            if (rowId == -1L && Metric.valueOf(e.metric) in MAX_WINS) samples.raiseTo(e.metric, e.epochMs, e.value, e.source)
        }
    }

    fun samples(metric: Metric, from: Instant, to: Instant): Flow<List<Sample>> =
        samples.between(metric.name, from.toEpochMilli(), to.toEpochMilli()).map { l -> l.map { it.toDomain() } }

    fun sleepSessions(from: Instant, to: Instant): Flow<List<SleepSession>> =
        sleep.sessionsBetween(from.toEpochMilli(), to.toEpochMilli()).map { sessions ->
            val segs = if (sessions.isEmpty()) emptyList() else sleep.segmentsFor(sessions.map { it.startMs })
            val bySession = segs.groupBy { it.sessionStartMs }
            sessions.map { s ->
                SleepSession(
                    Instant.ofEpochMilli(s.startMs), Instant.ofEpochMilli(s.endMs),
                    bySession[s.startMs].orEmpty().map {
                        SleepSegment(SleepStage.valueOf(it.stage), Instant.ofEpochMilli(it.startMs), it.durationSec)
                    },
                )
            }
        }

    fun workouts(from: Instant, to: Instant): Flow<List<WorkoutRecord>> =
        workouts.between(from.toEpochMilli(), to.toEpochMilli()).map { l ->
            l.map {
                WorkoutRecord(
                    Instant.ofEpochMilli(it.startMs), Instant.ofEpochMilli(it.endMs),
                    it.steps, it.distanceM, it.kcal, it.mode, it.avgHr,
                )
            }
        }

    suspend fun latest(metric: Metric): Sample? = samples.latest(metric.name)?.toDomain()

    /** Up to [limit] samples of [metric] strictly after [afterMs], oldest first (server sync). */
    suspend fun samplesAfter(metric: Metric, afterMs: Long, limit: Int): List<Sample> =
        samples.after(metric.name, afterMs, limit).map { it.toDomain() }

    /** Up to [limit] sleep sessions (with segments) starting at or after [fromMs], oldest first (server sync). */
    suspend fun sleepFrom(fromMs: Long, limit: Int): List<SleepSession> {
        val sessions = sleep.sessionsFrom(fromMs, limit)
        if (sessions.isEmpty()) return emptyList()
        val bySession = sleep.segmentsFor(sessions.map { it.startMs }).groupBy { it.sessionStartMs }
        return sessions.map { s ->
            SleepSession(
                Instant.ofEpochMilli(s.startMs), Instant.ofEpochMilli(s.endMs),
                bySession[s.startMs].orEmpty().map {
                    SleepSegment(SleepStage.valueOf(it.stage), Instant.ofEpochMilli(it.startMs), it.durationSec)
                },
            )
        }
    }

    /** The newest row id of [metric] (insertion order), or null with no rows (server sync). */
    suspend fun maxRowId(metric: Metric): Long? = samples.maxId(metric.name)

    /** Rows of [metric] with id in ([afterId], [upToId]] and time at or before [notAfterMs], as (row id, sample). */
    suspend fun insertedLate(metric: Metric, afterId: Long, upToId: Long, notAfterMs: Long, limit: Int): List<Pair<Long, Sample>> =
        samples.insertedLate(metric.name, afterId, upToId, notAfterMs, limit).map { it.id to it.toDomain() }

    /** Row counts, for diagnostics (debug logs). */
    suspend fun counts(): StoreCounts = StoreCounts(
        samplesByMetric = samples.countsByMetric().associate { it.metric to it.count },
        sleepSessions = sleep.sessionCount(),
        sleepSegments = sleep.segmentCount(),
        workouts = workouts.count(),
    )

    private fun Sample.toEntity() = SampleEntity(metric = metric.name, epochMs = time.toEpochMilli(), value = value, source = source.name)
    private fun SampleEntity.toDomain() =
        Sample(Metric.valueOf(metric), Instant.ofEpochMilli(epochMs), value, SampleSource.valueOf(source))
}

/** How far (either way) a ring's history copy of an on-demand reading may lie from the stored MEASURED sample. */
const val MEASURED_COPY_WINDOW_MS = 120_000L

/** Whole numbers for heart rate, SpO2, HRV and blood pressure; one decimal for everything else (e.g. stress). */
private val WHOLE_NUMBER_METRICS =
    setOf(Metric.HEART_RATE, Metric.SPO2, Metric.HRV, Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA)

/** The value a reading and the ring's history copy of it are compared by. */
internal fun dedupKey(metric: Metric, value: Double): Long =
    if (metric in WHOLE_NUMBER_METRICS) value.roundToLong() else (value * 10).roundToLong()

/** Metrics whose stored value is raised when the ring later reports a larger value for the same timestamp. */
val MAX_WINS = setOf(Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL, Metric.STEPS_DAY_TOTAL)

data class StoreCounts(
    val samplesByMetric: Map<String, Int>,
    val sleepSessions: Int,
    val sleepSegments: Int,
    val workouts: Int,
)
