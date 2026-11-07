package dev.avijit.orbithealth.ui.agent

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The access log reads as words, not as the server's query string. */
class AccessLogCopyTest {
    private val london = ZoneId.of("Europe/London")

    @Test fun metricReadsAsAnEnglishLine() {
        assertEquals(
            "Heart rate · hourly · 6 Nov 00:00–23:59 · 9 rows",
            AccessLogCopy.what(
                "get_metric", "metric=HEART_RATE from=2026-11-06T00:00:00Z to=2026-11-06T23:59:59.595817262Z agg=hourly", "9 rows", london,
            ),
        )
        assertEquals("SpO₂ · raw · 5 Nov 22:00 – 6 Nov 07:00 · 1 row",
            AccessLogCopy.what("get_metric", "metric=SPO2 from=2026-11-05T22:00:00Z to=2026-11-06T07:00:00Z agg=raw", "1 row", london))
    }

    @Test fun sleepReadsAsNightsAndDates() {
        assertEquals("Sleep · night of 5 Nov · 1 row", AccessLogCopy.what("get_sleep", "night=2026-11-05", "1 row", london))
        assertEquals("Sleep · 1 Nov – 6 Nov · 6 rows", AccessLogCopy.what("get_sleep_range", "from=2026-11-01 to=2026-11-06", "6 rows", london))
    }

    @Test fun anythingElseIsShownAsReported() {
        assertEquals("get metric · HEART_RATE avg · 24 rows", AccessLogCopy.what("get_metric", "HEART_RATE avg", "24 rows", london))
        assertEquals("ingest · samples · 1 row", AccessLogCopy.what("ingest", "samples", "1 row", london))
    }
}
