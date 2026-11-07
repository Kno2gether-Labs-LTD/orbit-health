package dev.avijit.orbithealth.sync

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    private val boot = Intent.ACTION_BOOT_COMPLETED
    private val replaced = Intent.ACTION_MY_PACKAGE_REPLACED

    @Test fun startsAfterBootWhenPairedAndGranted() {
        assertTrue(BootReceiver.shouldStartSync(boot, paired = true, bluetoothGranted = true))
        assertTrue(BootReceiver.shouldStartSync(replaced, paired = true, bluetoothGranted = true))
    }

    @Test fun doesNotStartWithoutPairedRing() {
        assertFalse(BootReceiver.shouldStartSync(boot, paired = false, bluetoothGranted = true))
    }

    @Test fun doesNotStartWithoutBluetoothConnect() {
        assertFalse(BootReceiver.shouldStartSync(boot, paired = true, bluetoothGranted = false))
    }

    @Test fun ignoresOtherActions() {
        assertFalse(BootReceiver.shouldStartSync(Intent.ACTION_SCREEN_ON, paired = true, bluetoothGranted = true))
        assertFalse(BootReceiver.shouldStartSync(null, paired = true, bluetoothGranted = true))
    }
}
