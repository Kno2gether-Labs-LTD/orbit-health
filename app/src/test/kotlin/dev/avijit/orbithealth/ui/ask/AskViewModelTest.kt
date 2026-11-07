package dev.avijit.orbithealth.ui.ask

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
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
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.ToolUse
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = LocalDateTime.of(2026, 11, 5, 9, 30).atZone(zone).toInstant()
    private val clock = Clock.fixed(now, zone)
    private val home = ServerInfo("https://orbit.home.lan:8443", "Home Mac", "ab".repeat(32), "d1", Scope.entries.toSet())

    private inner class Fake {
        val paired = MutableStateFlow<ServerInfo?>(home)
        val asked = mutableListOf<Pair<String, List<ChatTurn>>>()
        var answer: (String) -> AskResponse = { AskResponse("You slept well.", emptyList(), "scripted") }
        val queries = mutableListOf<Triple<Metric, Instant, Instant>>()
        var local: List<Sample> = emptyList()

        fun deps() = AskDeps(
            paired = paired,
            load = { paired.value },
            ask = { q, history -> asked += q to history; answer(q) },
            samples = { m, from, to -> queries += Triple(m, from, to); local },
            clock = clock,
            zone = zone,
        )
    }

    private fun TestScope.vm(f: Fake) = AskViewModel(f.deps(), backgroundScope).also { runCurrent() }

    private fun local(d: String) = LocalDateTime.parse(d).atZone(zone).toInstant()

    @Test fun notPairedAsksToConnectAServer() = runTest {
        val f = Fake().apply { paired.value = null }
        val vm = vm(f)
        assertEquals(AskConnection.NOT_PAIRED, vm.state.value.connection)
        vm.send("How did I sleep?")
        runCurrent()
        assertTrue("nothing is sent without a server", f.asked.isEmpty())

        f.paired.value = home
        runCurrent()
        assertEquals(AskConnection.PAIRED, vm.state.value.connection)
        assertEquals("Home Mac", vm.state.value.serverName)
    }

    @Test fun askShowsProvenance() = runTest {
        val f = Fake().apply {
            answer = {
                AskResponse(
                    "Your HRV has been steady.",
                    listOf(
                        ToolUse("get_metric", mapOf("metric" to "HRV", "from" to "2026-11-03", "to" to "2026-11-05", "agg" to "daily")),
                        ToolUse("compare_to_baseline", mapOf("metric" to "HEART_RATE", "days" to "1")),
                    ),
                    "claude-sonnet-5-5",
                )
            }
        }
        val vm = vm(f)
        vm.send("  Am I recovering well?  ")
        runCurrent()
        val msgs = vm.state.value.messages
        assertEquals(AskMessage.User::class, msgs[0]::class)
        assertEquals("Am I recovering well?", (msgs[0] as AskMessage.User).text)
        val agent = msgs[1] as AskMessage.Agent
        assertEquals("Your HRV has been steady.", agent.text)
        assertEquals("Based on: HRV, heart rate baseline · 3–5 Nov", agent.provenance)
        assertFalse(vm.state.value.sending)
    }

    @Test fun provenanceRangesAndNames() {
        val p = AskProvenance(clock, zone)
        assertEquals("Based on: sleep · 4 Nov", p.label(listOf(ToolUse("get_sleep", mapOf("night" to "last")))))
        assertEquals("Based on: daily summary · 5 Nov", p.label(listOf(ToolUse("daily_summary", emptyMap()))))
        assertEquals(
            "Based on: heart rate, SpO₂ · 28 Oct – 5 Nov",
            p.label(listOf(
                ToolUse("get_metric", mapOf("metric" to "HEART_RATE", "from" to "2026-10-28T08:00", "to" to "now")),
                ToolUse("get_metric", mapOf("metric" to "SPO2", "from" to "2026-11-02")),
            )),
        )
        assertEquals("Based on: stress (estimate) · 4–5 Nov", p.label(listOf(ToolUse("get_metric", mapOf("metric" to "STRESS")))))
        assertEquals("Based on: your list of metrics", p.label(listOf(ToolUse("list_metrics", emptyMap()))))
        assertNull("no tools, no chip", p.label(emptyList()))
        // Unparseable dates still name the data, just without a range.
        assertEquals("Based on: HRV", p.label(listOf(ToolUse("get_metric", mapOf("metric" to "HRV", "from" to "soon-ish")))))
    }

    @Test fun miniChartRequeriesTheLocalDatabaseForTheSameRange() = runTest {
        val f = Fake().apply {
            answer = {
                AskResponse(
                    "Your average heart rate was 999 bpm.", // the model's number is never charted
                    listOf(ToolUse("get_metric", mapOf("metric" to "HEART_RATE", "from" to "2026-11-04T09:30", "to" to "2026-11-05T09:30", "agg" to "avg"))),
                    "scripted",
                )
            }
            local = listOf(58.0, 61.0, 64.0, 60.0).mapIndexed { i, v ->
                Sample(Metric.HEART_RATE, local("2026-11-04T10:00").plusSeconds(i * 3_600L), v, SampleSource.HISTORY)
            }
        }
        val vm = vm(f)
        vm.send("What was my heart rate yesterday?")
        runCurrent()
        assertEquals(listOf(Triple(Metric.HEART_RATE, local("2026-11-04T09:30"), local("2026-11-05T09:30"))), f.queries)
        val chart = (vm.state.value.messages[1] as AskMessage.Agent).chart
        assertNotNull(chart)
        assertEquals(Metric.HEART_RATE, chart!!.metric)
        assertEquals(listOf(58f, 61f, 64f, 60f), chart.values)
        assertEquals("Heart rate on this phone · 4–5 Nov", chart.label)
    }

    @Test fun noChartWithoutGetMetricOrLocalData() = runTest {
        val f = Fake().apply { answer = { AskResponse("Deep sleep was short.", listOf(ToolUse("get_sleep", mapOf("night" to "2026-11-04"))), "m") } }
        val vm = vm(f)
        vm.send("Why was my deep sleep short?")
        runCurrent()
        assertNull((vm.state.value.messages[1] as AskMessage.Agent).chart)
        assertTrue(f.queries.isEmpty())

        f.answer = { AskResponse("No data.", listOf(ToolUse("get_metric", mapOf("metric" to "SPO2"))), "m") }
        f.local = emptyList()
        vm.send("SpO2?")
        runCurrent()
        assertNull("nothing on this phone for that range: no chart", (vm.state.value.messages[3] as AskMessage.Agent).chart)
    }

    @Test fun serverProblemsAreShownAndCanBeRetried() = runTest {
        val cases = listOf(
            ServerUnreachable(IOException("refused")) to AskProblem.UNREACHABLE,
            NoModel() to AskProblem.NO_MODEL,
            ModelError() to AskProblem.MODEL_ERROR,
            RateLimited() to AskProblem.RATE_LIMITED,
            ServerIdentityChanged("a", "b") to AskProblem.IDENTITY,
            ServerUnauthorized() to AskProblem.UNAUTHORIZED,
        )
        for ((error, problem) in cases) {
            val f = Fake().apply { answer = { throw error } }
            val vm = vm(f)
            vm.send("How did I sleep?")
            runCurrent()
            val p = vm.state.value.messages.last() as AskMessage.Problem
            assertEquals(error.javaClass.simpleName, problem, p.problem)
            assertFalse(vm.state.value.sending)
        }
        assertEquals("Choose a model in your Orbit Server dashboard", AskProblem.NO_MODEL.message)
        assertEquals("Server identity changed — re-pair", AskProblem.IDENTITY.message)

        // Retry sends the same question again and replaces the problem with the answer.
        val f = Fake()
        var fail = true
        f.answer = { if (fail) throw ServerUnreachable(IOException("x")) else AskResponse("Back again.", emptyList(), "m") }
        val vm = vm(f)
        vm.send("How did I sleep?")
        runCurrent()
        val problem = vm.state.value.messages.last() as AskMessage.Problem
        fail = false
        vm.retry(problem)
        runCurrent()
        assertEquals(listOf("How did I sleep?", "How did I sleep?"), f.asked.map { it.first })
        val msgs = vm.state.value.messages
        assertEquals(2, msgs.size)
        assertEquals("Back again.", (msgs[1] as AskMessage.Agent).text)
    }

    @Test fun losingThePairingMidConversationShowsNotPaired() = runTest {
        val f = Fake().apply { answer = { throw NotPaired() } }
        val vm = vm(f)
        vm.send("Hi")
        runCurrent()
        assertEquals(AskConnection.NOT_PAIRED, vm.state.value.connection)
    }

    @Test fun historySendsTheLastTurnsOfThisSession() = runTest {
        val f = Fake()
        var n = 0
        f.answer = { q -> if (q == "fail") throw RateLimited() else AskResponse("a${++n}", emptyList(), "m") }
        val vm = vm(f)
        vm.send("q1"); runCurrent()
        vm.send("fail"); runCurrent()
        vm.send("q2"); runCurrent()
        assertEquals(listOf(ChatTurn("user", "q1"), ChatTurn("assistant", "a1")), f.asked[2].second)
        repeat(8) { vm.send("more $it"); runCurrent() }
        val last = f.asked.last().second
        assertEquals("capped", AskViewModel.MAX_HISTORY_TURNS, last.size)
        assertEquals("user", last.first().role)
        assertEquals(ChatTurn("assistant", "a${n - 1}"), last.last())

        vm.send("   ")
        runCurrent()
        assertEquals("blank questions are not sent", 11, f.asked.size)
    }

    @Test fun historyTrimsLongTurnsAndStaysUnderTheByteCap() = runTest {
        val f = Fake()
        val long = "é".repeat(10_000) // 2 bytes each in UTF-8
        f.answer = { AskResponse(long, emptyList(), "m") }
        val vm = vm(f)
        repeat(7) { vm.send("q$it " + "x".repeat(5_000)); runCurrent() }
        val history = f.asked.last().second
        assertTrue(history.isNotEmpty())
        assertTrue("each turn ≤ 4,000 chars", history.all { it.text.length <= AskViewModel.MAX_TURN_CHARS })
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ChatTurn.serializer()), history,
        )
        assertTrue("history ${json.toByteArray().size} B ≤ cap", json.toByteArray().size <= AskViewModel.MAX_HISTORY_BYTES)
        assertEquals("starts with a question", "user", history.first().role)
        assertEquals("keeps the newest exchange", "q5 ", history[history.size - 2].text.take(3))
        // A whole request (2,000-char question of 3-byte chars + history) stays under the server's 64 KB.
        val req = dev.avijit.orbithealth.sync.AskRequest("€".repeat(AskViewModel.MAX_QUESTION), history, "Europe/London")
        val size = kotlinx.serialization.json.Json.encodeToString(dev.avijit.orbithealth.sync.AskRequest.serializer(), req).toByteArray().size
        assertTrue("request $size B < 64 KB", size < 64 * 1024)
    }

    @Test fun aTooLongConversationOffersANewOne() = runTest {
        val f = Fake()
        var tooLong = false
        f.answer = { if (tooLong) throw AskTooLarge() else AskResponse("ok", emptyList(), "m") }
        val vm = vm(f)
        vm.send("first"); runCurrent()
        tooLong = true
        vm.send("second"); runCurrent()
        val p = vm.state.value.messages.last() as AskMessage.Problem
        assertEquals(AskProblem.TOO_LONG, p.problem)
        assertTrue(p.problem.needsNewConversation)
        assertFalse(p.problem.needsSetup)
        assertTrue(p.problem.message.contains("too long"))

        vm.newConversation()
        runCurrent()
        assertTrue(vm.state.value.messages.isEmpty())
        tooLong = false
        vm.send("fresh"); runCurrent()
        assertEquals("a new conversation sends no history", emptyList<ChatTurn>(), f.asked.last().second)
    }

    @Test fun theConversationOutlivesTheScreenAndEndsWithTheServer() = runTest {
        val f = Fake()
        val session = AskSession()
        val first = AskViewModel(f.deps().let { AskDeps(it.paired, it.load, it.ask, it.samples, clock, zone, session) }, backgroundScope)
        runCurrent()
        first.send("q1"); runCurrent()
        assertEquals(2, first.state.value.messages.size)

        // The Ask screen left the back stack; a new one picks up the same conversation (and its history).
        val second = AskViewModel(f.deps().let { AskDeps(it.paired, it.load, it.ask, it.samples, clock, zone, session) }, backgroundScope)
        runCurrent()
        assertEquals(first.state.value.messages, second.state.value.messages)
        second.send("q2"); runCurrent()
        assertEquals(2, f.asked.last().second.size)
        assertEquals(4, second.state.value.messages.map { it.id }.distinct().size)

        f.paired.value = null
        runCurrent()
        assertTrue("removing the server forgets it", session.messages().isEmpty())
    }

    private fun AskDeps.with(session: AskSession) = AskDeps(paired, load, ask, samples, clock, zone, session)

    @Test fun rePairToAnotherServerForgetsTheConversation() = runTest {
        val f = Fake()
        val session = AskSession()
        val vm = AskViewModel(f.deps().with(session), backgroundScope)
        runCurrent()
        vm.send("q1"); runCurrent()
        assertEquals(2, vm.state.value.messages.size)

        // Paired straight to another server (A → B never passes through null).
        f.paired.value = home.copy(deviceId = "d2", name = "Office VPS")
        runCurrent()
        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(session.messages().isEmpty())
        vm.send("q2"); runCurrent()
        assertEquals("nothing from the first server goes to the second", emptyList<ChatTurn>(), f.asked.last().second)

        // A screen created later, still bound to B, sees only B's conversation.
        val again = AskViewModel(f.deps().with(session), backgroundScope)
        runCurrent()
        assertEquals(listOf("q2"), again.state.value.messages.filterIsInstance<AskMessage.User>().map { it.text })
    }

    @Test fun anAnswerThatArrivesAfterARePairIsDropped() = runTest {
        val f = Fake()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = AskSession()
        val vm = AskViewModel(
            AskDeps(f.paired, { f.paired.value }, { q, h -> f.asked += q to h; gate.await(); AskResponse("old server's answer", emptyList(), "m") },
                { _, _, _ -> emptyList() }, clock, zone, session),
            backgroundScope,
        )
        runCurrent()
        vm.send("q1"); runCurrent()
        f.paired.value = home.copy(deviceId = "d2")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.sending)
        assertTrue(session.messages().isEmpty())
    }

    @Test fun removingTheServerWithoutAskOpenForgetsTheConversation() = runTest {
        val f = Fake()
        val session = AskSession()
        // The app wiring follows the pairing on its own.
        session.follow(f.paired.map { it?.deviceId }, backgroundScope)
        val screen = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        val vm = AskViewModel(f.deps().with(session), screen)
        runCurrent()
        vm.send("q1"); runCurrent()
        assertEquals(2, session.messages().size)

        // The Ask screen is gone (its scope cancelled); the server is removed, then another one paired.
        screen.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        runCurrent()
        f.paired.value = null
        runCurrent()
        assertTrue(session.messages().isEmpty())
        f.paired.value = home.copy(deviceId = "d2")
        runCurrent()
        val next = AskViewModel(f.deps().with(session), backgroundScope)
        runCurrent()
        assertTrue(next.state.value.messages.isEmpty())
        next.send("q2"); runCurrent()
        assertEquals(emptyList<ChatTurn>(), f.asked.last().second)

        // Re-pairing the same way (B → C) with no screen alive also forgets.
        val s2 = AskSession()
        val paired = MutableStateFlow<ServerInfo?>(home)
        s2.follow(paired.map { it?.deviceId }, backgroundScope)
        runCurrent()
        s2.save("d1", listOf(AskMessage.User(0, "secret")))
        paired.value = home.copy(deviceId = "d3")
        runCurrent()
        assertTrue(s2.messages().isEmpty())
    }

    @Test fun theChartReadsAtMostNinetyDays() = runTest {
        val f = Fake().apply {
            answer = { AskResponse("A long view.", listOf(ToolUse("get_metric", mapOf("metric" to "HRV", "from" to "2024-01-01", "to" to "2026-11-05", "agg" to "daily"))), "m") }
            local = listOf(40.0, 45.0).mapIndexed { i, v -> Sample(Metric.HRV, local("2026-11-01T08:00").plusSeconds(i * 86_400L), v, SampleSource.HISTORY) }
        }
        val vm = vm(f)
        vm.send("How has my HRV changed since last year?")
        runCurrent()
        val (_, from, to) = f.queries.single()
        val end = LocalDateTime.parse("2026-11-06T00:00").atZone(zone).toInstant().minusMillis(1)
        assertEquals(end, to)
        assertEquals(end.minus(java.time.Duration.ofDays(90)), from)
        val chart = (vm.state.value.messages[1] as AskMessage.Agent).chart!!
        assertEquals("the label says what was drawn", "HRV on this phone · 8 Aug – 5 Nov", chart.label)
        assertEquals("the chip still names what the agent read", "Based on: HRV · 1 Jan 2024 – 5 Nov 2026", (vm.state.value.messages[1] as AskMessage.Agent).provenance)
    }
}
