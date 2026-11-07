package dev.avijit.orbithealth.server

import dev.avijit.orbithealth.data.MAX_WINS
import dev.avijit.orbithealth.data.SampleRepository
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.sync.IngestBatch
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import dev.avijit.orbithealth.sync.scope
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the latest push's outcome across restarts ("Last sync" on Agent & sync). */
interface SyncResultStore {
    suspend fun load(): ServerSyncResult?
    suspend fun save(result: ServerSyncResult?)
}

/** [SyncResultStore] in the app's settings (DataStore), as JSON. A result that no longer parses reads as none. */
class SettingsSyncResultStore(private val settings: dev.avijit.orbithealth.prefs.Settings) : SyncResultStore {
    override suspend fun load(): ServerSyncResult? = settings.serverSyncResult()?.let {
        try {
            ServerClient.json.decodeFromString(ServerSyncResult.serializer(), it)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(result: ServerSyncResult?) =
        settings.setServerSyncResult(result?.let { ServerClient.json.encodeToString(ServerSyncResult.serializer(), it) }).let { }
}

/** Local data the server sync reads, oldest first. */
interface ServerSyncSource {
    suspend fun samplesAfter(metric: Metric, afterMs: Long, limit: Int): List<SampleDto>
    suspend fun sleepFrom(fromMs: Long, limit: Int): List<SleepDto>

    /** The newest local row id of [metric] (row ids follow insertion order); null with no rows, or untracked. */
    suspend fun maxRowId(metric: Metric): Long? = null

    /** (row id, sample) of [metric] with id in ([afterId], [upToId]] and time at or before [notAfterMs], by id. */
    suspend fun insertedLate(metric: Metric, afterId: Long, upToId: Long, notAfterMs: Long, limit: Int): List<Pair<Long, SampleDto>> =
        emptyList()
}

/** [ServerSyncSource] over the encrypted database. */
class RepositorySyncSource(private val repo: SampleRepository) : ServerSyncSource {
    override suspend fun samplesAfter(metric: Metric, afterMs: Long, limit: Int) =
        repo.samplesAfter(metric, afterMs, limit).map { SampleDto(it.metric.name, it.time.toEpochMilli(), it.value, it.source.name) }

    override suspend fun maxRowId(metric: Metric): Long? = repo.maxRowId(metric)

    override suspend fun insertedLate(metric: Metric, afterId: Long, upToId: Long, notAfterMs: Long, limit: Int) =
        repo.insertedLate(metric, afterId, upToId, notAfterMs, limit).map { (id, it) ->
            id to SampleDto(it.metric.name, it.time.toEpochMilli(), it.value, it.source.name)
        }

    override suspend fun sleepFrom(fromMs: Long, limit: Int) = repo.sleepFrom(fromMs, limit).map { s ->
        SleepDto(s.start.toEpochMilli(), s.end.toEpochMilli(), s.segments.map { SegmentDto(it.stage.name, it.start.toEpochMilli(), it.durationSec) })
    }
}

/**
 * Outcome of one push to the server. [needsRepair]: the server's identity changed or it revoked this phone, so
 * automatic syncs stop until the user pairs again. [kind] names the failure for the UI (see [kindOf]).
 */
@Serializable
data class ServerSyncResult(
    val atMs: Long,
    val ok: Boolean,
    val samplesSent: Int = 0,
    val samplesNew: Int = 0,
    val sleepSent: Int = 0,
    val sleepNew: Int = 0,
    val error: String? = null,
    val needsRepair: Boolean = false,
    val kind: String? = null,
) {
    companion object {
        const val IDENTITY = "identity"
        const val UNAUTHORIZED = "unauthorized"
        const val UNREACHABLE = "unreachable"
        const val RATE_LIMITED = "rate_limited"
        const val OTHER = "other"

        fun kindOf(e: Throwable): String = when (e) {
            is ServerIdentityChanged -> IDENTITY
            is ServerUnauthorized -> UNAUTHORIZED
            is ServerUnreachable -> UNREACHABLE
            is RateLimited -> RATE_LIMITED
            else -> OTHER
        }
    }
}

/**
 * Pushes new local data to the paired server after each ring sync.
 *
 * One run: `GET /v1/state` (the server's newest timestamp per metric and newest sleep start) → for each metric the
 * phone's scopes allow, samples strictly newer than the server's watermark → sleep sessions starting at or after the
 * server's newest one (re-sent so a lengthened night or late segments arrive; the server extends, never duplicates)
 * → the ring's [deviceInfo] (profile scope) → `POST /v1/ingest` in chunks of at most [CHUNK_SAMPLES] samples /
 * [CHUNK_SLEEP] sessions. Activity metrics whose stored value only grows ([MAX_WINS]: the current 30-minute bucket,
 * the day's running total) are re-sent from [MAX_WINS_LOOKBACK_MS] before the watermark, since the server keeps the
 * larger value. Every batch carries the phone's IANA [zone].
 *
 * Late arrivals: a watermark is a time, but rows are not stored in time order. A Measure sheet reading is stored at
 * "now" while the ring's history up to now only lands at the next ring sync; a push in between moves the watermark past
 * that history. So each push also sends, per metric, the rows inserted since the previous successful push (row ids
 * above the stored [ServerStore.pushCursor], which follow insertion order) whose time is at or before the watermark.
 * The server dedups by (metric, time), so overlap is harmless. The cursor (the newest row id per metric when the push
 * began) is saved only when the whole push succeeded, and belongs to one pairing. [MAX_WINS] metrics keep their
 * look-back instead (their rows are raised in place, so a row id says nothing about a change).
 *
 * [afterRingSync] never blocks or fails the ring sync: it launches on [scope], skips while a push runs, during the
 * failure backoff (5 min doubling to 6 h) and once [ServerSyncResult.needsRepair]; exceptions are logged.
 *
 * Each pairing is a generation ([reset]). A push still in flight from an earlier pairing finishes against the old
 * server, but its outcome is dropped (no backoff, no re-pair block, not shown), and it then starts the new pairing's
 * push, which it would otherwise have locked out. The latest outcome is kept in [results] across restarts.
 */
class ServerSync(
    private val store: ServerStore,
    private val client: (PairedServer) -> ServerClient,
    private val source: ServerSyncSource,
    private val deviceInfo: () -> DeviceInfoDto?,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val zone: () -> String = { ZoneId.systemDefault().id },
    private val log: (String) -> Unit = {},
    private val results: SyncResultStore? = null,
) {
    private val running = Mutex()
    private val _last = MutableStateFlow<ServerSyncResult?>(null)
    val lastResult: StateFlow<ServerSyncResult?> = _last.asStateFlow()

    @Volatile private var failures = 0
    @Volatile private var nextAttemptAtMs = 0L
    @Volatile private var blocked = false
    private val generation = AtomicInteger()
    /** Orders writes to [results]: a reset's clear always lands after any save from the generation it ended. */
    private val persistLock = Mutex()

    init {
        if (results != null) {
            scope.launch {
                try {
                    persistLock.withLock {
                        val gen = generation.get()
                        val loaded = results.load()
                        // Paired again while reading: the stored outcome belongs to the old pairing. A restored
                        // "needs re-pair" outcome keeps automatic pushes stopped, as it did before the restart.
                        if (loaded != null && gen == generation.get() && _last.compareAndSet(null, loaded)) {
                            blocked = loaded.needsRepair
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("could not read the last sync result: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Fire-and-forget push after the ring's batch is committed. Safe to call from anywhere; never throws. */
    fun afterRingSync() {
        if (blocked || clock.millis() < nextAttemptAtMs) return
        scope.launch {
            try {
                if (store.current() != null) syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("server sync crashed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Starts a new generation (a new pairing, or none): forgets backoff, the re-pair block and the last outcome. */
    suspend fun reset() {
        generation.incrementAndGet()
        failures = 0
        nextAttemptAtMs = 0
        blocked = false
        _last.value = null
        persist(null, generation.get())
    }

    /** One push now. Null if no server is paired or a push is already running. Never throws (except cancellation). */
    suspend fun syncNow(): ServerSyncResult? {
        if (!running.tryLock()) return null
        var stale = false
        try {
            val gen = generation.get()
            val server = store.current() ?: return null
            val result = try {
                push(server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val repair = e is ServerIdentityChanged || e is ServerUnauthorized
                ServerSyncResult(
                    clock.millis(), ok = false, error = e.message ?: e.javaClass.simpleName, needsRepair = repair,
                    kind = ServerSyncResult.kindOf(e),
                )
            }
            if (gen != generation.get()) {
                stale = true // paired again (or unpaired) meanwhile: this outcome belongs to the old server
                log("dropped a push result from an earlier pairing")
                return null
            }
            record(result)
            persist(result, gen)
            return result
        } finally {
            running.unlock()
            if (stale) afterRingSync() // the new pairing's push was locked out by this one
        }
    }

    /** Saves [r] only while [gen] is still the current generation (checked under [persistLock]). */
    private suspend fun persist(r: ServerSyncResult?, gen: Int) {
        if (results == null) return
        try {
            persistLock.withLock { if (gen == generation.get()) results.save(r) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("could not save the last sync result: ${e.javaClass.simpleName}")
        }
    }

    private fun record(r: ServerSyncResult) {
        _last.value = r
        blocked = r.needsRepair
        if (r.ok) {
            failures = 0
            nextAttemptAtMs = 0
            log("server sync ok: ${r.samplesSent} samples, ${r.sleepSent} nights sent")
        } else {
            failures++
            val backoff = (BACKOFF_BASE_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(BACKOFF_MAX_MS)
            nextAttemptAtMs = clock.millis() + backoff
            log("server sync failed (${r.error}); next try in ${backoff / 60_000} min")
        }
    }

    private suspend fun push(server: PairedServer): ServerSyncResult {
        val api = client(server)
        // Always filtered by the user's own choice on this phone, never by the server's echo alone.
        val scopes = server.effectiveScopes.mapNotNull(Scope::fromWire).toSet()
        val state = api.state()
        val zoneId = zone()
        var device = deviceInfo()?.takeIf { Scope.PROFILE in scopes }
        var samplesSent = 0; var samplesNew = 0; var sleepSent = 0; var sleepNew = 0

        suspend fun send(samples: List<SampleDto>, sleep: List<SleepDto>) {
            val r = api.ingest(IngestBatch(samples, sleep, device, zoneId))
            device = null // once per push
            samplesSent += samples.size; samplesNew += r.samplesNew
            sleepSent += sleep.size; sleepNew += r.sleepNew
        }

        val known = store.pushCursor(server.deviceId)
        val covered = HashMap<String, Long>(known ?: emptyMap())
        val buf = ArrayList<SampleDto>(CHUNK_SAMPLES)
        for (metric in Metric.entries) {
            if (metric.scope() !in scopes) continue
            val watermark = state.lastEpochMsByMetric[metric.name]
            // Taken before reading: anything inserted later is newer than this push's cursor and goes next time.
            val top = if (metric in MAX_WINS) null else source.maxRowId(metric)
            var cursor = when {
                watermark == null -> 0L
                metric in MAX_WINS -> (watermark - MAX_WINS_LOOKBACK_MS - 1).coerceAtLeast(0L)
                else -> watermark
            }
            while (true) {
                val page = source.samplesAfter(metric, cursor, CHUNK_SAMPLES - buf.size)
                if (page.isEmpty()) break
                buf += page
                cursor = page.last().epochMs
                if (buf.size >= CHUNK_SAMPLES) {
                    send(buf.toList(), emptyList())
                    buf.clear()
                }
            }
            if (top == null) continue
            val from = known?.get(metric.name)
            // No server watermark: everything was just sent by time. No cursor (first push of this pairing, or before
            // this existed): the watermark alone, as before; the cursor starts at [top].
            if (watermark != null && from != null && from < top) {
                var after: Long = from
                while (true) {
                    val late = source.insertedLate(metric, after, top, watermark, CHUNK_SAMPLES - buf.size)
                    if (late.isEmpty()) break
                    late.mapTo(buf) { it.second }
                    after = late.last().first
                    if (buf.size >= CHUNK_SAMPLES) {
                        send(buf.toList(), emptyList())
                        buf.clear()
                    }
                }
            }
            covered[metric.name] = top
        }
        if (buf.isNotEmpty()) send(buf.toList(), emptyList())

        if (Scope.SLEEP in scopes) {
            var from = state.lastSleepStartMs ?: 0L
            while (true) {
                val page = source.sleepFrom(from, CHUNK_SLEEP)
                if (page.isEmpty()) break
                send(emptyList(), page)
                from = page.last().startMs + 1
            }
        }
        if (device != null) send(emptyList(), emptyList()) // nothing new, but the battery / firmware may have changed
        if (covered != known) {
            try {
                store.setPushCursor(server.deviceId, covered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("could not save the push cursor: ${e.javaClass.simpleName}") // the next push resends; harmless
            }
        }

        return ServerSyncResult(clock.millis(), ok = true, samplesSent, samplesNew, sleepSent, sleepNew)
    }

    companion object {
        const val CHUNK_SAMPLES = 2_000
        const val CHUNK_SLEEP = 50
        const val MAX_WINS_LOOKBACK_MS = 24 * 3_600_000L
        private const val BACKOFF_BASE_MS = 5 * 60_000L
        private const val BACKOFF_MAX_MS = 6 * 3_600_000L
    }
}
