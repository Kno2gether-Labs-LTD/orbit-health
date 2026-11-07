package dev.avijit.orbithealth.ui.agent

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plain words for the server's access log. The server reports what it read as `key=value` details
 * (`metric=HEART_RATE from=2026-11-06T00:00:00Z to=2026-11-06T23:59:59.59Z agg=hourly`); the row reads
 * "Heart rate · hourly · 6 Nov 00:00–23:59 · 9 rows". Anything not in that shape is shown as reported.
 */
object AccessLogCopy {
    private val day = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    fun what(action: String, detail: String, rowsLabel: String, zone: ZoneId): String {
        val kv = parse(detail)
        val lead = when {
            kv == null -> null
            action == "get_metric" && kv["metric"] != null -> metricName(kv.getValue("metric"))
            action == "get_sleep" || action == "get_sleep_range" -> "Sleep"
            else -> null
        }
        if (kv == null || lead == null) {
            return (listOf(action.replace('_', ' '), detail.trim()).filter { it.isNotEmpty() } + rowsLabel).joinToString(" · ")
        }
        val parts = mutableListOf(lead)
        kv["agg"]?.takeIf { it.isNotBlank() }?.let { parts += it.lowercase(Locale.UK) }
        kv["night"]?.let { n -> date(n)?.let { parts += "night of ${it.format(day)}" } ?: run { parts += n } }
        range(kv["from"], kv["to"], zone)?.let { parts += it }
        parts += rowsLabel
        return parts.joinToString(" · ")
    }

    /** "HEART_RATE" → "Heart rate"; "SPO2" → "SpO₂"; "HRV" stays. */
    fun metricName(wire: String): String = when (wire.uppercase(Locale.UK)) {
        "SPO2" -> "SpO₂"
        "HRV" -> "HRV"
        else -> wire.lowercase(Locale.UK).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.UK) }
    }

    /** "6 Nov 00:00–23:59" on one day, "5 Nov 22:00 – 6 Nov 07:00" across days, "1 Nov – 6 Nov" for dates. */
    fun range(from: String?, to: String?, zone: ZoneId): String? {
        if (from == null || to == null) return null
        val a = instant(from)
        val b = instant(to)
        if (a != null && b != null) {
            val za = a.atZone(zone)
            val zb = b.atZone(zone)
            return if (za.toLocalDate() == zb.toLocalDate()) "${za.format(day)} ${za.format(hm)}–${zb.format(hm)}"
            else "${za.format(day)} ${za.format(hm)} – ${zb.format(day)} ${zb.format(hm)}"
        }
        val da = date(from) ?: return null
        val db = date(to) ?: return null
        return if (da == db) da.format(day) else "${da.format(day)} – ${db.format(day)}"
    }

    private fun parse(detail: String): Map<String, String>? {
        val tokens = detail.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any { '=' !in it }) return null
        return tokens.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun instant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()
        ?: s.toLongOrNull()?.let { Instant.ofEpochMilli(it) }

    private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()
}
