package dev.avijit.orbithealth.ycbt

import org.json.JSONObject
import java.io.File

/** Decodes a hex string (case-insensitive, no separators) into bytes. */
fun hex(s: String): ByteArray {
    require(s.length % 2 == 0) { "odd-length hex: $s" }
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Lowercase hex encoding of this byte array. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

data class FixtureFrame(val dir: String, val char: String, val bytes: ByteArray)

/** All frames of fixtures/<name>.json, in capture order. */
fun fixtureFrames(name: String): List<FixtureFrame> {
    val url = checkNotNull(Thread.currentThread().contextClassLoader?.getResource("fixtures/$name.json")) {
        "missing fixture $name"
    }
    val frames = JSONObject(File(url.toURI()).readText()).getJSONArray("frames")
    return (0 until frames.length()).map { i ->
        val f = frames.getJSONObject(i)
        FixtureFrame(f.getString("dir"), f.getString("char"), hex(f.getString("hex")))
    }
}

/** Bytes of the rx frames of fixtures/<name>.json, in capture order. */
fun fixtureRx(name: String): List<ByteArray> =
    fixtureFrames(name).filter { it.dir == "rx" }.map { it.bytes }
