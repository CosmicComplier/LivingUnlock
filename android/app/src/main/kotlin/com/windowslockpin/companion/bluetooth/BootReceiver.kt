package com.windowslockpin.companion.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.windowslockpin.companion.core.model.SafeLogger

/**
 * Restarts the background BLE scan after device reboot or app update,
 * so the PC lock-screen beacon is detected without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            SafeLogger.i("BootReceiver", "Restarting background BLE scan after: $action")
            BleUnlockScanManager.startScan(context)
        }
    }
}
