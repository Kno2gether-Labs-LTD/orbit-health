package dev.avijit.orbithealth.ble

import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.GattProfile
import java.io.IOException

/**
 * A family's [GattProfile] applied to a [GattQueue]: which characteristics to subscribe to after connecting (across
 * any number of services), and where (and how) to write and read. Kept apart from [BleRingTransport] so it is
 * testable with fake GATT ops.
 */
internal class GattProfileLink(val profile: GattProfile, private val queue: GattQueue) {
    /** Subscribes to every notify characteristic, in profile order. */
    suspend fun subscribe() = profile.notifies.forEach { queue.enableIndication(GattTarget.of(it)) }

    /** Writes one command to the profile's default write characteristic. Throws for a write-less profile. */
    suspend fun send(bytes: ByteArray) {
        send(profile.defaultWrite ?: throw IOException("This ring takes no writes"), bytes)
    }

    /** Writes one command to [char], which must be one of the profile's writes. */
    suspend fun send(char: GattChar, bytes: ByteArray) {
        if (char !in profile.writes) throw IOException("Not a write characteristic of this ring: $char")
        queue.write(GattTarget.of(char), bytes, profile.writeWithResponse)
    }

    /** Reads [char] once (any readable characteristic, e.g. battery level). */
    suspend fun read(char: GattChar): ByteArray = queue.read(GattTarget.of(char))
}
