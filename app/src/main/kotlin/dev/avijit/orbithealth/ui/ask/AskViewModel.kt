package dev.avijit.orbithealth.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.server.AskTooLarge
import dev.avijit.orbithealth.server.ModelError
import dev.avijit.orbithealth.server.NoModel
import dev.avijit.orbithealth.server.NotPaired
import dev.avijit.orbithealth.server.RateLimited
import dev.avijit.orbithealth.server.ServerIdentityChanged
import dev.avijit.orbithealth.server.ServerInfo
import dev.avijit.orbithealth.server.ServerUnauthorized
import dev.avijit.orbithealth.server.ServerUnreachable
import dev.avijit.orbithealth.sync.AskResponse
import dev.avijit.orbithealth.sync.ChatTurn
import dev.avijit.orbithealth.sync.ToolUse
import dev.avijit.orbithealth.ui.agent.AgentSyncCopy
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The conversation for this app session, kept in memory only (never stored) and tied to one pairing (the paired
 * device id): it outlives the Ask screen leaving the back stack, and is dropped as soon as the pairing changes (another
 * server, a re-pair, or none), so one server's conversation is never sent as history to another.
 */
class AskSession {
    private var pairing: String? = null
    private var messages: List<AskMessage> = emptyList()
    @Volatile var nextId: Long = 0

    /** Binds to [deviceId] (null: not paired); a different pairing drops the conversation. Returns its messages. */
    @Synchronized
    fun bind(deviceId: String?): List<AskMessage> {
        if (deviceId != pairing) {
            pairing = deviceId
            messages = emptyList()
        }
        return messages
    }

    /** Saves [list] as the conversation with [deviceId], unless the session has moved to another pairing meanwhile. */
    @Synchronized
    fun save(deviceId: String?, list: List<AskMessage>) {
        if (deviceId != null && deviceId == pairing) messages = list
    }

    @Synchronized
    fun messages(): List<AskMessage> = messages

    /** App wiring: follow the paired device id (null: none) whether or not an Ask screen is alive. */
    fun follow(deviceIds: Flow<String?>, scope: CoroutineScope): Job =
        scope.launch { deviceIds.distinctUntilChanged().collect { bind(it) } }
}

/** What Ask needs: the paired server, its agent (only via ServerRepository), and the local database for charts. */
class AskDeps(
    val paired: Flow<ServerInfo?>,
    val load: suspend () -> ServerInfo?,
    val ask: suspend (question: String, history: List<ChatTurn>) -> AskResponse,
    /** Local samples for [Metric] in [from, to] (the mini chart re-reads them; it never charts the model's numbers). */
    val samples: suspend (Metric, Instant, Instant) -> List<Sample>,
    val clock: Clock,
    val zone: ZoneId,
    val session: AskSession = AskSession(),
)

enum class AskConnection { LOADING, NOT_PAIRED, PAIRED }

/** Why a question got no answer; [message] is what the conversation shows. */
enum class AskProblem(val message: String) {
    UNREACHABLE("Can't reach your Orbit Server right now. Check that it's running, then try again."),
    NO_MODEL(AgentSyncCopy.NO_MODEL),
    MODEL_ERROR(AgentSyncCopy.MODEL_ERROR),
    RATE_LIMITED("You've asked a lot in a short time. Try again in a minute."),
    IDENTITY(AgentSyncCopy.IDENTITY),
    UNAUTHORIZED(AgentSyncCopy.UNAUTHORIZED),
    TOO_LONG("This conversation got too long for your server — start a new one."),
    OTHER("Something went wrong on your server. Try again."),
    ;

    /** These need Agent & sync (re-pair or set up a model there), not just another try. */
    val needsSetup: Boolean get() = this == IDENTITY || this == UNAUTHORIZED

    /** Trying again sends the same history again: only a new conversation helps. */
    val needsNewConversation: Boolean get() = this == TOO_LONG

    companion object {
        fun of(e: Throwable): AskProblem = when (e) {
            is ServerUnreachable -> UNREACHABLE
            is NoModel -> NO_MODEL
            is ModelError -> MODEL_ERROR
            is RateLimited -> RATE_LIMITED
            is AskTooLarge -> TOO_LONG
            is ServerIdentityChanged -> IDENTITY
            is ServerUnauthorized -> UNAUTHORIZED
            else -> OTHER
        }
    }
}

/** A small trend under an answer, from this phone's own data for the range the agent read. */
data class AskChart(val metric: Metric, val label: String, val values: List<Float>)

sealed interface AskMessage {
    val id: Long

    data class User(override val id: Long, val text: String) : AskMessage

    /** [provenance]: "Based on: HRV, resting HR · 3–5 Nov"; [chart] when the agent read a metric. */
    data class Agent(override val id: Long, val text: String, val provenance: String?, val chart: AskChart?) : AskMessage

    data class Problem(override val id: Long, val problem: AskProblem, val question: String) : AskMessage
}

data class AskUiState(
    val connection: AskConnection = AskConnection.LOADING,
    val serverName: String? = null,
    val messages: List<AskMessage> = emptyList(),
    val sending: Boolean = false,
)

/**
 * The questions offered before the first one (screen-12's layout). Each one is answerable from any history, even a
 * first night: no named weekdays or workouts the ring may never have recorded.
 */
val AskSuggestions = listOf(
    "How did I sleep last night?",
    "Am I recovered enough for a hard workout today?",
    "How has my resting heart rate changed this week?",
)

/**
 * Turns an answer's tool calls into the provenance chip and the mini chart's range, resolving dates the way the
 * server's tools do (ISO dates, local date-times in [zone], offset date-times, "now", "today", "yesterday"/"last").
 */
class AskProvenance(private val clock: Clock, private val zone: ZoneId) {
    data class Range(val from: Instant, val to: Instant)

    /** "Based on: HRV, heart rate · 3–5 Nov", or null when no tool was used. */
    fun label(tools: List<ToolUse>): String? {
        if (tools.isEmpty()) return null
        val names = tools.mapNotNull(::name).distinct()
        if (names.isEmpty()) return null
        val ranges = tools.mapNotNull(::range)
        val dates = if (ranges.isEmpty()) null else dates(Range(ranges.minOf { it.from }, ranges.maxOf { it.to }))
        return "Based on: ${names.joinToString(", ")}" + (dates?.let { " · $it" } ?: "")
    }

    /** "5 Nov", "3–5 Nov", "28 Oct – 3 Nov", "1 Jan 2024 – 5 Nov 2026" (local dates). */
    fun dates(r: Range): String {
        val a = r.from.atZone(zone).toLocalDate()
        val b = r.to.atZone(zone).toLocalDate()
        return when {
            a == b -> DAY_MONTH.format(a)
            a.year == b.year && a.month == b.month -> "${a.dayOfMonth}–${DAY_MONTH.format(b)}"
            a.year == b.year -> "${DAY_MONTH.format(a)} – ${DAY_MONTH.format(b)}"
            else -> "${DAY_MONTH_YEAR.format(a)} – ${DAY_MONTH_YEAR.format(b)}"
        }
    }

    /** The time range a tool call read, or null when it has none or its dates don't parse. */
    fun range(t: ToolUse): Range? = try {
        when (t.tool) {
            "get_metric" -> {
                val to = t.args["to"]?.takeIf { it.isNotBlank() }?.let { time(it, end = true) } ?: clock.instant()
                val from = t.args["from"]?.takeIf { it.isNotBlank() }?.let { time(it, end = false) } ?: to.minus(Duration.ofHours(24))
                if (from.isAfter(to)) null else Range(from, to)
            }
            "get_sleep" -> day(date(t.args["night"] ?: "last"))
            "daily_summary" -> day(date(t.args["date"] ?: "today"))
            "compare_to_baseline" -> {
                val days = t.args["days"]?.toDoubleOrNull()?.toLong()?.coerceIn(1, 365) ?: 14
                val today = today()
                Range(today.minusDays(days).atStartOfDay(zone).toInstant(), endOf(today))
            }
            else -> null
        }
    } catch (e: DateTimeException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun name(t: ToolUse): String? = when (t.tool) {
        "get_metric" -> metric(t)?.let(::metricName)
        "compare_to_baseline" -> metric(t)?.let { "${metricName(it)} baseline" }
        "get_sleep" -> "sleep"
        "daily_summary" -> "daily summary"
        "list_metrics" -> "your list of metrics"
        else -> null
    }

    fun metric(t: ToolUse): Metric? = t.args["metric"]?.let { m -> Metric.entries.firstOrNull { it.name == m.trim().uppercase() } }

    private fun day(d: LocalDate) = Range(d.atStartOfDay(zone).toInstant(), endOf(d))
    private fun endOf(d: LocalDate) = d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    private fun date(s: String): LocalDate = when (s.trim().lowercase()) {
        "today" -> today()
        "yesterday", "last", "last night", "lastnight" -> today().minusDays(1)
        else -> LocalDate.parse(s.trim())
    }

    private fun time(s: String, end: Boolean): Instant {
        val t = s.trim()
        if (t.equals("now", ignoreCase = true)) return clock.instant()
        if (t.length == 10 || t.all { it.isLetter() || it == ' ' }) {
            val d = date(t)
            return if (end) endOf(d) else d.atStartOfDay(zone).toInstant()
        }
        return try {
            OffsetDateTime.parse(t).toInstant()
        } catch (e: DateTimeException) {
            LocalDateTime.parse(t).atZone(zone).toInstant()
        }
    }

    companion object {
        private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
        private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

        /** Lower-case names for the chip; estimates say so. */
        fun metricName(m: Metric): String = when (m) {
            Metric.HEART_RATE -> "heart rate"
            Metric.HRV -> "HRV"
            Metric.SPO2 -> "SpO₂"
            Metric.STRESS -> "stress (estimate)"
            Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA -> "blood pressure (estimate)"
            Metric.RESPIRATORY_RATE -> "breathing rate"
            Metric.STEPS, Metric.STEPS_DAY_TOTAL -> "steps"
            Metric.DISTANCE_M -> "distance"
            Metric.CALORIES_KCAL -> "calories"
        }
    }
}

/**
 * Ask: a conversation with the agent on the user's Orbit Server, kept in memory for this session (the last
 * [MAX_HISTORY_TURNS] turns go with each question). Each answer shows where it came from and, when the agent read a
 * metric, a mini chart drawn from this phone's own data for the same range.
 */
class AskViewModel(private val deps: AskDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val provenance = AskProvenance(deps.clock, deps.zone)

    private data class Local(
        val loaded: Boolean = false,
        val lostPairing: Boolean = false,
        val messages: List<AskMessage> = emptyList(),
        val sending: Boolean = false,
        /** The pairing [messages] belong to (saved to the session only under it). */
        val pairing: String? = UNBOUND,
    )

    private val session = deps.session
    private val local = MutableStateFlow(Local())
    /** The pairing this screen's conversation belongs to; [UNBOUND] until the paired server is known. */
    @Volatile private var boundTo: String? = UNBOUND
    private var nextId: Long
        get() = session.nextId
        set(v) { session.nextId = v }

    val state: StateFlow<AskUiState> = combine(deps.paired, local) { server, l ->
        AskUiState(
            connection = when {
                server != null && !l.lostPairing -> AskConnection.PAIRED
                l.loaded || l.lostPairing -> AskConnection.NOT_PAIRED
                else -> AskConnection.LOADING
            },
            serverName = server?.name,
            messages = l.messages,
            sending = l.sending,
        )
    }.stateIn(this.scope, SharingStarted.Eagerly, AskUiState())

    init {
        this.scope.launch {
            try {
                deps.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable counts as not paired.
            }
            local.update { it.copy(loaded = true) }
        }
        // The conversation follows the pairing: another server (or none) starts empty; a new pairing clears a
        // lost-pairing state.
        this.scope.launch {
            deps.paired.collect { s ->
                val id = s?.deviceId
                if (id != boundTo) {
                    boundTo = id
                    val kept = session.bind(id)
                    local.update { it.copy(messages = kept, pairing = id) }
                }
                if (s != null) local.update { it.copy(lostPairing = false) }
            }
        }
        this.scope.launch { local.collect { session.save(it.pairing, it.messages) } }
    }

    fun send(raw: String) {
        val question = raw.trim().take(MAX_QUESTION)
        if (question.isEmpty() || local.value.sending || state.value.connection != AskConnection.PAIRED) return
        val history = history(local.value.messages)
        local.update { it.copy(messages = it.messages + AskMessage.User(nextId++, question), sending = true) }
        val pairing = boundTo
        scope.launch { answer(question, history, pairing) }
    }

    /** Starts over: drops this conversation (in the session too), so the next question goes without history. */
    fun newConversation() {
        if (local.value.sending) return
        local.update { it.copy(messages = emptyList()) }
    }

    /** Asks [p]'s question again in place of the problem. */
    fun retry(p: AskMessage.Problem) {
        if (local.value.sending) return
        val before = local.value.messages.takeWhile { it.id != p.id }
        // Drop the problem and the question it answered; the question goes again with the history before it.
        val trimmed = if (before.lastOrNull() is AskMessage.User) before.dropLast(1) else before
        local.update { it.copy(messages = trimmed) }
        send(p.question)
    }

    private suspend fun answer(question: String, history: List<ChatTurn>, pairing: String?) {
        val reply: AskMessage = try {
            val r = deps.ask(question, history)
            AskMessage.Agent(nextId++, r.answer, provenance.label(r.provenance), chart(r.provenance))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotPaired) {
            local.update { it.copy(sending = false, lostPairing = true) }
            return
        } catch (e: Exception) {
            AskMessage.Problem(nextId++, AskProblem.of(e), question)
        }
        if (boundTo != pairing) {
            // Paired with another server while waiting: this answer belongs to the old conversation.
            local.update { it.copy(sending = false) }
            return
        }
        local.update { it.copy(messages = it.messages + reply, sending = false) }
    }

    /** From the first metric the agent read: the same metric and range, read again from this phone's database. */
    private suspend fun chart(tools: List<ToolUse>): AskChart? {
        val call = tools.firstOrNull { it.tool == "get_metric" && provenance.metric(it) != null } ?: return null
        val metric = provenance.metric(call) ?: return null
        // At most the last MAX_CHART_DAYS of the range: a year of raw samples is never loaded for a 48-point sparkline.
        val range = provenance.range(call)?.let { r ->
            val earliest = r.to.minus(Duration.ofDays(MAX_CHART_DAYS))
            if (r.from.isBefore(earliest)) AskProvenance.Range(earliest, r.to) else r
        } ?: return null
        val values = try {
            deps.samples(metric, range.from, range.to).sortedBy { it.time }.map { it.value.toFloat() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (values.size < 2) return null
        val label = AskProvenance.metricName(metric).replaceFirstChar { it.uppercase() }
        return AskChart(metric, "$label on this phone · ${provenance.dates(range)}", downsample(values, MAX_CHART_POINTS))
    }

    companion object {
        private const val UNBOUND = "\u0000unbound"

        /** Turns of earlier conversation sent with a question (user/assistant pairs). */
        const val MAX_HISTORY_TURNS = 12
        /** Each history turn is cut to this many characters (long answers keep their start). */
        const val MAX_TURN_CHARS = 4_000
        /**
         * The history's encoded size (UTF-8 JSON of its turns) stays under this, dropping the oldest exchanges first,
         * so question + history stays well inside the server's 64 KB ask limit.
         */
        const val MAX_HISTORY_BYTES = 48 * 1024
        const val MAX_QUESTION = 2_000
        const val MAX_CHART_POINTS = 48
        const val MAX_CHART_DAYS = 90L

        /**
         * Completed exchanges only (a question with its answer), newest last, starting with a question: at most
         * [MAX_HISTORY_TURNS] turns of at most [MAX_TURN_CHARS] characters each, and at most [MAX_HISTORY_BYTES]
         * encoded (whole exchanges are dropped, oldest first).
         */
        fun history(messages: List<AskMessage>): List<ChatTurn> {
            val pairs = ArrayList<Pair<ChatTurn, ChatTurn>>()
            messages.forEachIndexed { i, m ->
                val next = messages.getOrNull(i + 1)
                if (m is AskMessage.User && next is AskMessage.Agent) {
                    pairs += ChatTurn("user", m.text.take(MAX_TURN_CHARS)) to ChatTurn("assistant", next.text.take(MAX_TURN_CHARS))
                }
            }
            val kept = ArrayDeque<Pair<ChatTurn, ChatTurn>>()
            var bytes = 0
            for (p in pairs.asReversed()) {
                if (kept.size * 2 >= MAX_HISTORY_TURNS) break
                val size = encodedSize(p.first) + encodedSize(p.second)
                if (bytes + size > MAX_HISTORY_BYTES) break
                bytes += size
                kept.addFirst(p)
            }
            return kept.flatMap { listOf(it.first, it.second) }
        }

        private val historyJson = kotlinx.serialization.json.Json { encodeDefaults = true }

        /** A turn's size as it goes on the wire (JSON escapes included), plus a separator. */
        private fun encodedSize(t: ChatTurn): Int =
            historyJson.encodeToString(ChatTurn.serializer(), t).toByteArray(Charsets.UTF_8).size + 1

        /** Averages [values] into at most [n] buckets, keeping their order. */
        fun downsample(values: List<Float>, n: Int): List<Float> {
            if (values.size <= n) return values
            return List(n) { b ->
                val from = b * values.size / n
                val to = ((b + 1) * values.size / n).coerceAtLeast(from + 1)
                values.subList(from, to).average().toFloat()
            }
        }
    }
}
