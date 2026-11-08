package dev.avijit.orbithealth.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    /** Returns each row id, or -1 where (metric, epochMs) already existed and the row was ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<SampleEntity>): List<Long>

    /** Raises an existing sample to [value] only if that is larger (the ring rewrote a partial bucket). */
    @Query("UPDATE samples SET value = :value, source = :source WHERE metric = :metric AND epochMs = :epochMs AND value < :value")
    suspend fun raiseTo(metric: String, epochMs: Long, value: Double, source: String)

    @Query("SELECT * FROM samples WHERE metric = :metric AND epochMs BETWEEN :fromMs AND :toMs ORDER BY epochMs")
    fun between(metric: String, fromMs: Long, toMs: Long): Flow<List<SampleEntity>>

    /** Samples of [source] for any of [metrics] in [fromMs]..[toMs] (dedup of a ring's history copy of a reading). */
    @Query("SELECT * FROM samples WHERE metric IN (:metrics) AND source = :source AND epochMs BETWEEN :fromMs AND :toMs")
    suspend fun bySourceBetween(metrics: List<String>, source: String, fromMs: Long, toMs: Long): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE metric = :metric ORDER BY epochMs DESC LIMIT 1")
    suspend fun latest(metric: String): SampleEntity?

    /** Up to [limit] samples of [metric] strictly after [afterMs], oldest first (server sync paging). */
    @Query("SELECT * FROM samples WHERE metric = :metric AND epochMs > :afterMs ORDER BY epochMs LIMIT :limit")
    suspend fun after(metric: String, afterMs: Long, limit: Int): List<SampleEntity>

    /** The newest row id of [metric] (ids only grow: insertion order), or null with no rows. */
    @Query("SELECT MAX(id) FROM samples WHERE metric = :metric")
    suspend fun maxId(metric: String): Long?

    /**
     * Rows of [metric] inserted after row [afterId] (up to [upToId]) whose time is at or before [notAfterMs]: data
     * that arrived late, behind newer readings (server sync). In insertion order.
     */
    @Query(
        "SELECT * FROM samples WHERE metric = :metric AND id > :afterId AND id <= :upToId AND epochMs <= :notAfterMs " +
            "ORDER BY id LIMIT :limit",
    )
    suspend fun insertedLate(metric: String, afterId: Long, upToId: Long, notAfterMs: Long, limit: Int): List<SampleEntity>

    @Query("SELECT metric, COUNT(*) AS count FROM samples GROUP BY metric ORDER BY metric")
    suspend fun countsByMetric(): List<MetricCount>
}

@Dao
interface SleepDao {
    /** Returns each row id, or -1 where a session with that start already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessions(items: List<SleepSessionEntity>): List<Long>

    /** Extends an existing session's end (a provisional end rewritten later); never shortens it. */
    @Query("UPDATE sleep_sessions SET endMs = :endMs WHERE startMs = :startMs AND endMs < :endMs")
    suspend fun extendEnd(startMs: Long, endMs: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSegments(items: List<SleepSegmentEntity>)

    @Query("SELECT * FROM sleep_sessions WHERE startMs BETWEEN :fromMs AND :toMs ORDER BY startMs")
    fun sessionsBetween(fromMs: Long, toMs: Long): Flow<List<SleepSessionEntity>>

    /** Up to [limit] sessions starting at or after [fromMs], oldest first (server sync paging). */
    @Query("SELECT * FROM sleep_sessions WHERE startMs >= :fromMs ORDER BY startMs LIMIT :limit")
    suspend fun sessionsFrom(fromMs: Long, limit: Int): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_segments WHERE sessionStartMs IN (:starts) ORDER BY startMs")
    suspend fun segmentsFor(starts: List<Long>): List<SleepSegmentEntity>

    @Query("SELECT COUNT(*) FROM sleep_sessions")
    suspend fun sessionCount(): Int

    @Query("SELECT COUNT(*) FROM sleep_segments")
    suspend fun segmentCount(): Int
}

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WorkoutEntity>)

    @Query("SELECT * FROM workouts WHERE startMs BETWEEN :fromMs AND :toMs ORDER BY startMs")
    fun between(fromMs: Long, toMs: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT COUNT(*) FROM workouts")
    suspend fun count(): Int
}

@Dao
interface NetworkLogDao {
    @Insert
    suspend fun insert(entry: NetworkLogEntity)

    /** Newest first. */
    @Query("SELECT * FROM network_log ORDER BY atMs DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NetworkLogEntity>>

    /** Total bytes up plus down since [fromMs] (0 when there were none). */
    @Query("SELECT COALESCE(SUM(bytesUp + bytesDown), 0) FROM network_log WHERE atMs >= :fromMs")
    fun bytesSince(fromMs: Long): Flow<Long>

    /** Bytes sent (up only) to the user's server since [fromMs]; app-update requests to GitHub are not counted. */
    @Query("SELECT COALESCE(SUM(bytesUp), 0) FROM network_log WHERE atMs >= :fromMs AND purpose NOT LIKE 'update%'")
    fun bytesUpSince(fromMs: Long): Flow<Long>

    @Query("DELETE FROM network_log WHERE atMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int
}

data class MetricCount(val metric: String, val count: Int)

@Dao
interface SleepEstimateDao {
    @Insert
    suspend fun insert(e: SleepEstimateEntity): Long

    /** Refreshes a suggestion with a newer detection; decided estimates are never touched. */
    @Query(
        "UPDATE sleep_estimates SET kind = :kind, startMs = :startMs, endMs = :endMs, detectedStartMs = :startMs, " +
            "detectedEndMs = :endMs, lowestHr = :lowestHr, lowestSpo2 = :lowestSpo2, confidence = :confidence " +
            "WHERE id = :id AND status = 'SUGGESTED'",
    )
    suspend fun refreshSuggestion(id: Long, kind: String, startMs: Long, endMs: Long, lowestHr: Int?, lowestSpo2: Int?, confidence: Double)

    @Query("DELETE FROM sleep_estimates WHERE id IN (:ids) AND status = 'SUGGESTED'")
    suspend fun deleteSuggestions(ids: List<Long>)

    /** Keeps a suggestion, with the user's times. Returns rows changed (0 when it was already decided or is gone). */
    @Query(
        "UPDATE sleep_estimates SET status = 'CONFIRMED', startMs = :startMs, endMs = :endMs, decidedAtMs = :atMs " +
            "WHERE id = :id AND status = 'SUGGESTED'",
    )
    suspend fun confirm(id: Long, startMs: Long, endMs: Long, atMs: Long): Int

    /** Turns a suggestion down for good. Returns rows changed. */
    @Query("UPDATE sleep_estimates SET status = 'DISMISSED', decidedAtMs = :atMs WHERE id = :id AND status = 'SUGGESTED'")
    suspend fun dismiss(id: Long, atMs: Long): Int

    /** Estimates ending in [fromMs]..[toMs], oldest first. */
    @Query("SELECT * FROM sleep_estimates WHERE endMs BETWEEN :fromMs AND :toMs ORDER BY startMs")
    fun between(fromMs: Long, toMs: Long): Flow<List<SleepEstimateEntity>>

    /** Estimates whose time overlaps [fromMs]..[toMs] (the detector's window), any status. */
    @Query("SELECT * FROM sleep_estimates WHERE endMs >= :fromMs AND startMs <= :toMs ORDER BY startMs")
    suspend fun overlapping(fromMs: Long, toMs: Long): List<SleepEstimateEntity>

    /** Every answered estimate in the order the user answered (the tuning's memory). */
    @Query("SELECT * FROM sleep_estimates WHERE status != 'SUGGESTED' ORDER BY decidedAtMs, id")
    suspend fun decided(): List<SleepEstimateEntity>

    @Query("SELECT * FROM sleep_estimates WHERE id = :id")
    suspend fun byId(id: Long): SleepEstimateEntity?

    @Query("SELECT COUNT(*) FROM sleep_estimates")
    suspend fun count(): Int
}
