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
 * One request the app made over the internet (to the user's paired Orbit Server, or to GitHub for app updates): when,
 * to which host, why ([purpose]: pair, state, ingest, ask, access_log, scopes, revoke; update_check, update_checksum,
 * update_download), HTTP bytes each way, and whether it succeeded.
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

/**
 * A sleep the app estimated because the ring recorded none (see insights/SleepEstimator), and what the user made of it.
 * [startMs]..[endMs] are shown (the user's times once adjusted); [detectedStartMs]..[detectedEndMs] were suggested.
 * [kind]: NIGHT or NAP. [status]: SUGGESTED, CONFIRMED or DISMISSED. [decidedAtMs]: when the user answered (null while
 * suggested). Never exported: the ring's own sleep tables are what the server receives.
 */
@Entity(tableName = "sleep_estimates", indices = [Index(value = ["endMs"])])
data class SleepEstimateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val status: String,
    val startMs: Long,
    val endMs: Long,
    val detectedStartMs: Long,
    val detectedEndMs: Long,
    val lowestHr: Int?,
    val lowestSpo2: Int?,
    val confidence: Double,
    val createdAtMs: Long,
    val decidedAtMs: Long?,
)
