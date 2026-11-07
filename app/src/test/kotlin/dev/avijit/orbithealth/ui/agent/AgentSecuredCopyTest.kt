package dev.avijit.orbithealth.ui.agent

import dev.avijit.orbithealth.server.PairingRejected
import dev.avijit.orbithealth.server.ServerInfo
import dev.avijit.orbithealth.server.ServerSyncResult
import dev.avijit.orbithealth.server.ServerTrustMode
import dev.avijit.orbithealth.server.ServerUnreachable
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Secured" status row on Agent & sync, per trust mode. */
class AgentSecuredCopyTest {
    @Test fun publicCertificateServersSaySo() {
        assertEquals("Secured · public certificate", AgentSyncCopy.secured(null, ServerTrustMode.PUBLIC_CA))
    }

    @Test fun pinnedServersKeepTheirCopy() {
        assertEquals("Paired · pinned certificate", AgentSyncCopy.secured(null, ServerTrustMode.PIN))
    }

    @Test fun repairStatesWinInBothModes() {
        for (mode in ServerTrustMode.entries) {
            assertEquals("Certificate changed · not trusted", AgentSyncCopy.secured(AgentSyncCopy.IDENTITY, mode))
            assertEquals("Removed on the server", AgentSyncCopy.secured(AgentSyncCopy.UNAUTHORIZED, mode))
        }
    }
}

/** Unreachable copy: a public-certificate server gets an online/certificate hint instead of the same-network one. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentUnreachableCopyTest {
    private val unreachable = ServerUnreachable(IOException("refused"))
    private val caText = "Can't reach your Orbit Server. Check that it's online and its certificate is valid for orbit.example.com."
    private val pinServer = ServerInfo("https://orbit.home.lan:8443", "Home Mac", "ab".repeat(32), "d1", emptySet())
    private val caServer = ServerInfo(
        "https://orbit.example.com", "VPS", null, "d2", emptySet(), ServerTrustMode.PUBLIC_CA,
    )

    @Test fun copyDependsOnTheTrustMode() {
        assertEquals(caText, AgentSyncCopy.of(unreachable, "orbit.example.com"))
        assertEquals(AgentSyncCopy.UNREACHABLE, AgentSyncCopy.of(unreachable))
        assertEquals(caText, AgentSyncCopy.ofKind(ServerSyncResult.UNREACHABLE, "orbit.example.com"))
        assertEquals(AgentSyncCopy.UNREACHABLE, AgentSyncCopy.ofKind(ServerSyncResult.UNREACHABLE))
        assertEquals(null, pinServer.caHost())
        assertEquals("orbit.example.com", caServer.caHost())
        // Other failures are unaffected by the mode.
        assertEquals(AgentSyncCopy.REJECTED, AgentSyncCopy.of(PairingRejected(), "orbit.example.com"))
    }

    private fun deps(paired: MutableStateFlow<ServerInfo?>, lastSync: ServerSyncResult?) =
        AgentSyncDeps(
            paired = paired,
            lastSync = flowOf(lastSync),
            load = { paired.value },
            pair = { _, _ -> throw unreachable },
            unpair = { true },
            setScopes = { throw unreachable },
            accessLog = { throw unreachable },
            syncNow = { null },
            clock = Clock.fixed(Instant.parse("2026-11-06T09:00:00Z"), ZoneOffset.UTC),
            now = flowOf(Instant.parse("2026-11-06T09:00:00Z")),
        )

    @Test fun pairingACaLinkThatCannotBeReachedNamesItsHost() = runTest {
        val vm = AgentSyncViewModel(deps(MutableStateFlow(null), null), backgroundScope)
        runCurrent()
        vm.pair("orbit://pair?u=https%3A%2F%2Forbit.example.com&t=tok&n=VPS&m=ca")
        runCurrent()
        assertEquals(caText, vm.state.value.notice)
        vm.pair("orbit://pair?u=https%3A%2F%2Forbit.home.lan%3A8443&fp=${"ab".repeat(32)}&t=tok&n=Home")
        runCurrent()
        assertEquals(AgentSyncCopy.UNREACHABLE, vm.state.value.notice)
    }

    @Test fun aPairedCaServersFailedPushAndAccessLogNameItsHost() = runTest {
        val failed = ServerSyncResult(atMs = 0, ok = false, kind = ServerSyncResult.UNREACHABLE)
        val vm = AgentSyncViewModel(deps(MutableStateFlow(caServer), failed), backgroundScope)
        runCurrent()
        assertEquals(caText, vm.state.value.lastSyncCaption)
        assertEquals(AccessLogUi.Failed(caText), vm.state.value.accessLog)

        val vmPin = AgentSyncViewModel(deps(MutableStateFlow(pinServer), failed), backgroundScope)
        runCurrent()
        assertEquals(AgentSyncCopy.UNREACHABLE, vmPin.state.value.lastSyncCaption)
        assertEquals(AccessLogUi.Failed(AgentSyncCopy.UNREACHABLE), vmPin.state.value.accessLog)
    }
}
