package dev.avijit.orbithealth.server

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** [serverHttpFor]: the one trust-mode → client selector used by AppGraph and ServerRepository. */
class ServerHttpSelectorTest {
    private val pinnedClient = OkHttpClient()
    private val caClient = OkHttpClient()
    private val pinnedCalls = mutableListOf<String>()
    private val caCalls = mutableListOf<String>()
    private val pinned: (String) -> OkHttpClient = { pinnedCalls += it; pinnedClient }
    private val ca: (String) -> OkHttpClient = { caCalls += it; caClient }
    private val fp = "ab".repeat(32)

    @Test fun pinModeUsesThePinnedClientForTheFingerprint() {
        val s = PairedServer("https://orbit.home.lan:8443", fp, "t", "d", "n", emptySet())
        assertSame(pinnedClient, serverHttpFor(s, pinned, ca))
        assertEquals(listOf(fp), pinnedCalls)
        assertEquals(emptyList<String>(), caCalls)
    }

    @Test fun publicCaModeUsesTheCaClientForTheUrl() {
        val s = PairedServer("https://orbit.example.com", null, "t", "d", "n", emptySet(), trustMode = ServerTrustMode.PUBLIC_CA)
        assertSame(caClient, serverHttpFor(s, pinned, ca))
        assertEquals(listOf("https://orbit.example.com"), caCalls)
        assertEquals(emptyList<String>(), pinnedCalls)
    }

    @Test fun inconsistentRequestsNeverFallBackToTheOtherMode() {
        for ((mode, f) in listOf(ServerTrustMode.PIN to null, ServerTrustMode.PUBLIC_CA to fp)) {
            try {
                serverHttpFor(mode, f, "https://x", pinned, ca)
                fail("$mode with fingerprint $f must be refused")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(emptyList<String>(), pinnedCalls + caCalls)
    }
}
