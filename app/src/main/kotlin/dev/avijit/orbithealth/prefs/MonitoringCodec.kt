package dev.avijit.orbithealth.prefs

import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitoringConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An automatic-check schedule as JSON: the one the paired ring last ACCEPTED (rings that can't report their schedule,
 * like the TK5, are shown it as "last set from Orbit Health"), and the one the user wants. Unknown kinds are skipped;
 * anything malformed decodes to null. `overnightOnly` is written only when true, so older values still decode.
 */
object MonitoringCodec {
    private const val VERSION = 1

    fun encode(c: MonitoringConfig): String = buildJsonObject {
        put("v", VERSION)
        put("settings", JsonObject(c.settings.entries.associate { (k, v) ->
            k.name to buildJsonObject {
                put("enabled", v.enabled)
                put("intervalMin", v.intervalMin)
                if (v.overnightOnly) put("overnightOnly", true)
            }
        }))
    }.toString()

    fun decode(json: String?): MonitoringConfig? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = Json.parseToJsonElement(json).jsonObject
            if (o["v"]?.jsonPrimitive?.int != VERSION) return null
            MonitoringConfig(
                o.getValue("settings").jsonObject.entries.mapNotNull { (k, v) ->
                    val kind = MonitorKind.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
                    val s = v.jsonObject
                    kind to MonitorSetting(
                        s.getValue("enabled").jsonPrimitive.boolean,
                        s.getValue("intervalMin").jsonPrimitive.int,
                        s["overnightOnly"]?.jsonPrimitive?.boolean ?: false,
                    )
                }.toMap(),
            )
        } catch (e: Exception) {
            null
        }
    }
}
