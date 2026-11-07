package dev.avijit.orbithealth.prefs

import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MeasureSpec
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.RingCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [RingCapabilities] as JSON, cached with the paired ring so capability-driven screens work offline. Enum values a
 * later or earlier build doesn't know are skipped; anything malformed decodes to null (the cache is then just empty
 * until the next handshake).
 */
object CapabilitiesCodec {
    private const val VERSION = 1

    fun encode(c: RingCapabilities): String = buildJsonObject {
        put("v", VERSION)
        put("history", JsonArray(c.history.map { JsonPrimitive(it.name) }))
        put("sleep", c.sleep)
        put("workouts", c.workouts)
        put("realtime", c.realtime)
        put("measure", JsonObject(c.measure.entries.associate { (k, v) ->
            k.name to buildJsonObject {
                put("typicalSeconds", v.typicalSeconds)
                put("live", v.live)
                put("estimate", v.estimate)
            }
        }))
        put("monitoring", JsonObject(c.monitoring.entries.associate { (k, v) ->
            k.name to buildJsonObject {
                put("intervalsMin", JsonArray(v.intervalsMin.map { JsonPrimitive(it) }))
                put("canTurnOff", v.canTurnOff)
                put("supportsWindow", v.supportsWindow)
            }
        }))
        put("canClearHistory", c.canClearHistory)
    }.toString()

    fun decode(json: String?): RingCapabilities? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = Json.parseToJsonElement(json).jsonObject
            if (o["v"]?.jsonPrimitive?.int != VERSION) return null
            fun flag(key: String) = o.getValue(key).jsonPrimitive.boolean
            RingCapabilities(
                history = o.getValue("history").jsonArray.mapNotNullTo(mutableSetOf()) { enumOrNull<Metric>(it.jsonPrimitive.content) },
                sleep = flag("sleep"),
                workouts = flag("workouts"),
                realtime = flag("realtime"),
                measure = o.getValue("measure").jsonObject.entries.mapNotNull { (k, v) ->
                    val kind = enumOrNull<MeasureKind>(k) ?: return@mapNotNull null
                    val s = v.jsonObject
                    kind to MeasureSpec(
                        typicalSeconds = s.getValue("typicalSeconds").jsonPrimitive.int,
                        live = s.getValue("live").jsonPrimitive.boolean,
                        estimate = s.getValue("estimate").jsonPrimitive.boolean,
                    )
                }.toMap(),
                monitoring = o.getValue("monitoring").jsonObject.entries.mapNotNull { (k, v) ->
                    val kind = enumOrNull<MonitorKind>(k) ?: return@mapNotNull null
                    val s = v.jsonObject
                    kind to MonitorSpec(
                        intervalsMin = s.getValue("intervalsMin").jsonArray.map { it.jsonPrimitive.int },
                        canTurnOff = s.getValue("canTurnOff").jsonPrimitive.boolean,
                        supportsWindow = s.getValue("supportsWindow").jsonPrimitive.boolean,
                    )
                }.toMap(),
                canClearHistory = flag("canClearHistory"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
