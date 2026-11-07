package dev.avijit.orbithealth.ui.agent

import dev.avijit.orbithealth.server.InvalidPairingLink
import dev.avijit.orbithealth.server.PairingRejected
import dev.avijit.orbithealth.server.ServerHttpError
import dev.avijit.orbithealth.server.ServerIdentityChanged
import dev.avijit.orbithealth.server.ServerInfo
import dev.avijit.orbithealth.server.ServerSyncResult
import dev.avijit.orbithealth.server.ServerUnauthorized
import dev.avijit.orbithealth.server.ServerUnreachable
import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.Scope
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSyncViewModelTest {
    private val now = Instant.parse("2026-11-06T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val home = ServerInfo("https://orbit.home.lan:8443", "Home Mac", "ab".repeat(32), "d1", Scope.entries.toSet())

    private inner class Fake {
        val paired = MutableStateFlow<ServerInfo?>(null)
        val lastSync = MutableStateFlow<ServerSyncResult?>(null)
        val pairCalls = mutableListOf<Pair<String, Set<Scope>>>()
        val scopeCalls = mutableListOf<Set<Scope>>()
        var pairResult: () -> ServerInfo = { home }
        var scopesResult: (Set<Scope>) -> Set<Scope> = { it }
        var unpairResult = true
        var unpaired = 0
        var log: () -> List<AccessLogEntry> = { emptyList() }
        var syncs = 0

        fun deps() = AgentSyncDeps(
            paired = paired,
            lastSync = lastSync,
            load = { paired.value },
            pair = { link, scopes ->
                pairCalls += link to scopes
                pairResult().also { paired.value = it.copy(scopes = scopes) }
            },
            unpair = { unpaired++; paired.value = null; unpairResult },
            setScopes = { s ->
                scopeCalls += s
                scopesResult(s).also { r -> paired.value = paired.value?.copy(scopes = r) }
            },
            accessLog = { log() },
            syncNow = { syncs++; null },
            clock = clock,
            now = flowOf(now),
        )
    }

    private fun TestScope.vm(f: Fake) = AgentSyncViewModel(f.deps(), backgroundScope).also { runCurrent() }

    @Test fun notPairedOffersPairingWithTheChosenScopes() = runTest {
        val f = Fake()
        val vm = vm(f)
        val s = vm.state.value
        assertTrue(s.loaded)
        assertNull(s.server)
        assertEquals("every group is shared unless you switch it off", Scope.entries.toSet(), s.scopes)

        vm.toggleScope(Scope.PROFILE, false)
        runCurrent()
        assertTrue("no server call before pairing", f.scopeCalls.isEmpty())
        vm.pair("  orbit://pair?u=x  ")
        runCurrent()
        assertEquals(listOf("orbit://pair?u=x" to (Scope.entries.toSet() - Scope.PROFILE)), f.pairCalls)
        assertEquals(home.name, vm.state.value.server?.name)
        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.busy)
    }

    @Test fun scopeToggleCallsServer() = runTest {
        val f = Fake().apply { paired.value = home }
        val vm = vm(f)
        vm.toggleScope(Scope.SLEEP, false)
        runCurrent()
        assertEquals(listOf(Scope.entries.toSet() - Scope.SLEEP), f.scopeCalls)
        assertEquals(Scope.entries.toSet() - Scope.SLEEP, vm.state.value.scopes)

        vm.toggleScope(Scope.SLEEP, true)
        runCurrent()
        assertEquals(Scope.entries.toSet(), f.scopeCalls.last())
        assertEquals(Scope.entries.toSet(), vm.state.value.scopes)
    }

    @Test fun scopeToggleShowsThePendingChoiceThenFallsBackToWhatIsStoredOnFailure() = runTest {
        val f = Fake().apply { paired.value = home }
        val gate = CompletableDeferred<Unit>()
        val vm = AgentSyncViewModel(
            f.deps().copy(setScopes = { s -> f.scopeCalls += s; gate.await(); throw ServerUnreachable(IOException("down")) }),
            backgroundScope,
        )
        runCurrent()
        vm.toggleScope(Scope.HEART, false)
        runCurrent()
        assertEquals("the switch moves at once", Scope.entries.toSet() - Scope.HEART, vm.state.value.scopes)
        gate.complete(Unit)
        runCurrent()
        assertEquals("then shows what the phone really holds", Scope.entries.toSet(), vm.state.value.scopes)
        assertEquals(AgentSyncCopy.UNREACHABLE, vm.state.value.notice)
    }

    @Test fun pairingFailuresAreExplained() = runTest {
        val cases = listOf(
            InvalidPairingLink() to AgentSyncCopy.INVALID_LINK,
            PairingRejected() to AgentSyncCopy.REJECTED,
            ServerUnreachable(IOException("refused")) to AgentSyncCopy.UNREACHABLE,
            ServerIdentityChanged("a", "b") to "Server identity changed — re-pair",
            ServerHttpError(500, null) to AgentSyncCopy.OTHER,
        )
        for ((error, message) in cases) {
            val f = Fake().apply { pairResult = { throw error } }
            val vm = vm(f)
            vm.pair("orbit://pair?x")
            runCurrent()
            assertEquals(error.javaClass.simpleName, message, vm.state.value.notice)
            assertNull(vm.state.value.server)
            assertNull(vm.state.value.busy)
        }
    }

    @Test fun aCancelledScanDoesNothing() = runTest {
        val f = Fake()
        val vm = vm(f)
        vm.onScanned(null)
        vm.onScanned("   ")
        runCurrent()
        assertTrue(f.pairCalls.isEmpty())
        assertNull(vm.state.value.notice)
    }

    @Test fun identityChangeAndRemovalShowARepairBanner() = runTest {
        val f = Fake().apply { paired.value = home }
        val vm = vm(f)
        assertNull(vm.state.value.repair)
        f.lastSync.value = ServerSyncResult(now.toEpochMilli(), ok = false, needsRepair = true, kind = ServerSyncResult.IDENTITY)
        runCurrent()
        assertEquals("Server identity changed — re-pair", vm.state.value.repair)
        f.lastSync.value = ServerSyncResult(now.toEpochMilli(), ok = false, needsRepair = true, kind = ServerSyncResult.UNAUTHORIZED)
        runCurrent()
        assertEquals("This phone was removed from your server — pair again", vm.state.value.repair)

        // The same words when an action hits it.
        val g = Fake().apply { paired.value = home; scopesResult = { throw ServerUnauthorized() } }
        val vm2 = vm(g)
        vm2.toggleScope(Scope.STRESS, false)
        runCurrent()
        assertEquals("This phone was removed from your server — pair again", vm2.state.value.notice)
    }

    @Test fun lastSyncResultIsShown() = runTest {
        val f = Fake().apply { paired.value = home }
        val vm = vm(f)
        assertEquals("Not yet", vm.state.value.lastSync)
        f.lastSync.value = ServerSyncResult(now.minusSeconds(120).toEpochMilli(), ok = true, samplesSent = 1_200, sleepSent = 4)
        runCurrent()
        assertEquals("2 min ago", vm.state.value.lastSync)
        assertEquals("1,204 readings sent", vm.state.value.lastSyncCaption)
        f.lastSync.value = ServerSyncResult(now.toEpochMilli(), ok = true)
        runCurrent()
        assertEquals("Just now", vm.state.value.lastSync)
        assertEquals("Up to date", vm.state.value.lastSyncCaption)
        f.lastSync.value = ServerSyncResult(now.minusSeconds(300).toEpochMilli(), ok = false, kind = ServerSyncResult.UNREACHABLE)
        runCurrent()
        assertEquals("Failed 5 min ago", vm.state.value.lastSync)
        assertEquals(AgentSyncCopy.UNREACHABLE, vm.state.value.lastSyncCaption)
    }

    @Test fun syncNowPushes() = runTest {
        val f = Fake().apply { paired.value = home }
        val vm = vm(f)
        vm.syncNow()
        runCurrent()
        assertEquals(1, f.syncs)
        assertNull(vm.state.value.busy)
    }

    @Test fun removeServerUnpairs() = runTest {
        val f = Fake().apply { paired.value = home }
        val vm = vm(f)
        vm.remove()
        runCurrent()
        assertEquals(1, f.unpaired)
        assertNull(vm.state.value.server)
        assertNull(vm.state.value.notice)
        assertEquals(AccessLogUi.Hidden, vm.state.value.accessLog)

        // The server could not confirm: forgotten here, and the user is told to remove it there too.
        val g = Fake().apply { paired.value = home; unpairResult = false }
        val vm2 = vm(g)
        vm2.remove()
        runCurrent()
        assertNull(vm2.state.value.server)
        assertEquals(AgentSyncCopy.REMOVED_LOCALLY, vm2.state.value.notice)
    }

    @Test fun accessLogIsLoadedFromTheServer() = runTest {
        val f = Fake().apply {
            paired.value = home
            log = {
                listOf(
                    AccessLogEntry(now.minusSeconds(180).toEpochMilli(), "claude-code", "get_metric", "HEART_RATE avg", 24),
                    AccessLogEntry(now.minusSeconds(7_200).toEpochMilli(), "Pixel 9", "ingest", "samples", 1),
                )
            }
        }
        val vm = vm(f)
        val loaded = vm.state.value.accessLog as AccessLogUi.Loaded
        assertEquals(2, loaded.rows.size)
        assertEquals("claude-code", loaded.rows[0].who)
        assertEquals("get metric · HEART_RATE avg · 24 rows", loaded.rows[0].what)
        assertEquals("3 min ago", loaded.rows[0].time)
        assertEquals("ingest · samples · 1 row", loaded.rows[1].what)

        val g = Fake().apply { paired.value = home; log = { throw ServerUnreachable(IOException("x")) } }
        val vm2 = vm(g)
        assertEquals(AccessLogUi.Failed(AgentSyncCopy.UNREACHABLE), vm2.state.value.accessLog)

        val none = vm(Fake())
        assertEquals("no server, no log", AccessLogUi.Hidden, none.state.value.accessLog)
    }

    @Test fun quickScopeTapsBuildOnEachOtherAndReachTheServerInOrder() = runTest {
        val f = Fake().apply { paired.value = home }
        val gate = CompletableDeferred<Unit>()
        val vm = AgentSyncViewModel(
            f.deps().copy(setScopes = { s ->
                f.scopeCalls += s
                if (f.scopeCalls.size == 1) gate.await()
                s.also { f.paired.value = f.paired.value?.copy(scopes = it) }
            }),
            backgroundScope,
        )
        runCurrent()
        // Two taps before anything has round-tripped (no runCurrent between them).
        vm.toggleScope(Scope.HEART, false)
        vm.toggleScope(Scope.SLEEP, false)
        runCurrent()
        assertEquals("the second tap keeps the first", Scope.entries.toSet() - Scope.HEART - Scope.SLEEP, vm.state.value.scopes)
        assertEquals("one update at a time", 1, f.scopeCalls.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(
            listOf(Scope.entries.toSet() - Scope.HEART, Scope.entries.toSet() - Scope.HEART - Scope.SLEEP),
            f.scopeCalls,
        )
        assertEquals(Scope.entries.toSet() - Scope.HEART - Scope.SLEEP, vm.state.value.scopes)
    }

    @Test fun pairingNeedsAtLeastOneScope() = runTest {
        val f = Fake()
        val vm = vm(f)
        assertTrue(vm.state.value.canPair)
        Scope.entries.forEach { vm.toggleScope(it, false) }
        runCurrent()
        assertTrue(vm.state.value.scopes.isEmpty())
        assertTrue("Scan is disabled", !vm.state.value.canPair)
        vm.onScanned("orbit://pair?x")
        vm.pair("orbit://pair?y")
        runCurrent()
        assertTrue("nothing is sent to a server", f.pairCalls.isEmpty())
        assertEquals(AgentSyncCopy.NOTHING_SHARED, vm.state.value.notice)

        vm.toggleScope(Scope.SLEEP, true)
        runCurrent()
        assertTrue(vm.state.value.canPair)
        vm.pair("orbit://pair?z")
        runCurrent()
        assertEquals(setOf(Scope.SLEEP), f.pairCalls.single().second)
    }
}
