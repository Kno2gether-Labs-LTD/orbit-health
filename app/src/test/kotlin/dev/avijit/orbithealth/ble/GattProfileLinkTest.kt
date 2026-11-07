package dev.avijit.orbithealth.ble

import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.GattProfile
import dev.avijit.orbithealth.ring.fake.FakeFamily
import dev.avijit.orbithealth.ycbt.YcbtFamily
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GattProfileLinkTest {
    /** Fake GATT: records each op (service/characteristic) and completes it at once. [values] answers reads. */
    private class FakeGatt : GattOps {
        lateinit var queue: GattQueue
        val ops = mutableListOf<String>()
        val values = mutableMapOf<GattTarget, ByteArray>()
        private fun name(t: GattTarget) = "${t.service.toString().substring(4, 8)}/${t.characteristic.toString().substring(4, 8)}"
        override fun writeCharacteristic(target: GattTarget, value: ByteArray, withResponse: Boolean): Boolean {
            ops += "write ${name(target)} ${if (withResponse) "req" else "cmd"} ${value.joinToString("") { "%02x".format(it) }}"
            queue.onWriteComplete(0)
            return true
        }
        override fun writeDescriptor(target: GattTarget): Boolean {
            ops += "subscribe ${name(target)}"
            queue.onDescriptorComplete(0)
            return true
        }
        override fun readCharacteristic(target: GattTarget): Boolean {
            ops += "read ${name(target)}"
            queue.onReadComplete(0, values[target])
            return true
        }
    }

    private fun linkFor(profile: GattProfile): Pair<GattProfileLink, FakeGatt> {
        val gatt = FakeGatt()
        val queue = GattQueue(gatt).also { gatt.queue = it }
        return GattProfileLink(profile, queue) to gatt
    }

    @Test
    fun transportUsesFamilyGattProfile() = runTest {
        // A non-YCBT family: its own service, characteristics and write-without-response.
        val (link, gatt) = linkFor(FakeFamily().gattProfile)
        link.subscribe()
        link.send(byteArrayOf(0x01, 0x02))
        assertEquals(listOf("subscribe aaaa/aaac", "write aaaa/aaab cmd 0102"), gatt.ops)
    }

    @Test
    fun multiServiceProfileSubscribesAcrossServicesAndReads() = runTest {
        // Colmi-like: a command service and a separate big-data service, plus the standard battery characteristic.
        val cmdWrite = GattChar.sig("fff0", "0002")
        val cmdNotify = GattChar.sig("fff0", "0003")
        val bigWrite = GattChar.sig("f728", "0001")
        val bigNotify = GattChar.sig("f728", "0002")
        val battery = GattChar.sig("180f", "2a19")
        val family = FakeFamily(
            gattProfile = GattProfile(writes = listOf(cmdWrite, bigWrite), notifies = listOf(cmdNotify, bigNotify), writeWithResponse = false),
        )
        val (link, gatt) = linkFor(family.gattProfile)
        gatt.values[GattTarget.of(battery)] = byteArrayOf(54)
        link.subscribe()
        link.send(byteArrayOf(0x0a)) // default write: the command service
        link.send(bigWrite, byteArrayOf(0x0b))
        assertEquals(listOf<Byte>(54), link.read(battery).toList())
        assertEquals(
            listOf("subscribe fff0/0003", "subscribe f728/0002", "write fff0/0002 cmd 0a", "write f728/0001 cmd 0b", "read 180f/2a19"),
            gatt.ops,
        )
        try {
            link.send(battery, byteArrayOf(1)) // not one of the profile's writes
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun writeLessProfileOnlySubscribesAndReads() = runTest {
        val hr = GattChar.sig("180d", "2a37")
        val battery = GattChar.sig("180f", "2a19")
        val (link, gatt) = linkFor(GattProfile(writes = emptyList(), notifies = listOf(hr), writeWithResponse = false))
        gatt.values[GattTarget.of(battery)] = byteArrayOf(80)
        link.subscribe()
        assertEquals(listOf<Byte>(80), link.read(battery).toList())
        try {
            link.send(byteArrayOf(1))
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("no writes"))
        }
        assertEquals(listOf("subscribe 180d/2a37", "read 180f/2a19"), gatt.ops)
    }

    @Test
    fun ycbtProfileKeepsTheTk5Wiring() = runTest {
        val (link, gatt) = linkFor(YcbtFamily.gattProfile)
        link.subscribe()
        link.send(byteArrayOf(0x7f))
        // be940000 service, be940001 + be940003 subscribed, commands to be940001 with response (unchanged wire).
        assertEquals(listOf("subscribe 0000/0001", "subscribe 0000/0003", "write 0000/0001 req 7f"), gatt.ops)
    }
}

/** Needs Robolectric: the CCCD byte arrays are null in the plain android.jar stubs. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class CccdValueTest {
    @Test
    fun cccdPrefersIndicationAndFallsBackToNotification() {
        val indicate = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
        val notify = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val ind = android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val not = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        assertEquals(ind.toList(), cccdValueFor(indicate or notify).toList())
        assertEquals(ind.toList(), cccdValueFor(indicate).toList())
        assertEquals(not.toList(), cccdValueFor(notify).toList())
        assertEquals(ind.toList(), cccdValueFor(0).toList())
    }
}
