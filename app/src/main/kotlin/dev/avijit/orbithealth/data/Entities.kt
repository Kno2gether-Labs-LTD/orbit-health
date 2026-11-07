package dev.avijit.orbithealth.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "samples", indices = [Index(value = ["metric", "epochMs"], unique = true)])
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metric: String,
    val epochMs: Long,
    val value: Double,
    val source: String,
)

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(@PrimaryKey val startMs: Long, val endMs: Long)

@Entity(
    tableName = "sleep_segments",
    indices = [Index(value = ["sessionStartMs", "startMs"], unique = true)],
)
data class SleepSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionStartMs: Long,
    val stage: String,
    val startMs: Long,
    val durationSec: Int,
)

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val startMs: Long,
    val endMs: Long,
    val steps: Int,
    val distanceM: Int,
    val kcal: Int,
    val mode: Int?,
    val avgHr: Int?,
)

/**
 * One request the app made over the internet (only ever to the user's paired Orbit Server): when, to which host,
 * why ([purpose]: pair, state, ingest, ask, access_log, scopes, revoke), HTTP bytes each way, and whether it succeeded.
 * Feeds Data & privacy ("Sent to the internet this month").
 */
@Entity(tableName = "network_log")
data class NetworkLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val host: String,
    val purpose: String,
    val bytesUp: Long,
    val bytesDown: Long,
    val ok: Boolean,
)
