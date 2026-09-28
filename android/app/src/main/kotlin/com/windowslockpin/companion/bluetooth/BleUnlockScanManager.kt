package com.windowslockpin.companion.bluetooth

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.windowslockpin.companion.core.model.ProtocolConstants
import com.windowslockpin.companion.core.model.SafeLogger
import com.windowslockpin.companion.service.BluetoothUnlockService
import java.util.UUID

/**
 * Manages a background BLE scan filtered on [BLE_UNLOCK_SERVICE_UUID].
 *
 * The PC advertises this UUID when it is at the lock screen
 * (via BluetoothLEAdvertisementPublisher on the Windows side).
 *
 * Android delivers scan results via a [PendingIntent] even when the app is killed,
 * allowing near-instant notification without polling or keeping a foreground service
 * connected to the PC at all times.
 *
 * Usage:
 *   BleUnlockScanManager.startScan(context)   — called from Application.onCreate
 *   BleUnlockScanManager.stopScan(context)    — called when pairing is removed
 */
object BleUnlockScanManager {
    private const val TAG = "BleUnlockScanManager"

    /** Must match the GUID in windows/broker/ble_advertiser.h */
    val BLE_UNLOCK_SERVICE_UUID: UUID =
        UUID.fromString("a1b2c3d4-5678-9abc-def0-123456789abc")

    private const val SCAN_REQUEST_CODE = 9001
    private const val ACTION_BLE_SCAN_RESULT =
        "com.windowslockpin.companion.BLE_SCAN_RESULT"

    @SuppressLint("MissingPermission")
    fun startScan(context: Context) {
        if (!hasBluetoothPermissions(context)) {
            SafeLogger.w(TAG, "BLE scan deferred until Bluetooth permissions are granted")
            return
        }
        try {
            val scanner = getScanner(context) ?: return
            val pi = buildPendingIntent(context)

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BLE_UNLOCK_SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)   // battery-friendly
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH) // fire only on first sighting
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .build()

            val result = scanner.startScan(listOf(filter), settings, pi)
            if (result == 0) {
                SafeLogger.i(TAG, "Background BLE scan started (service UUID filter active)")
            } else {
                SafeLogger.w(TAG, "Background BLE scan start failed: error code $result")
            }
        } catch (e: SecurityException) {
            SafeLogger.w(TAG, "BLE scan deferred because Bluetooth permission was revoked")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan(context: Context) {
        if (!hasBluetoothPermissions(context)) return
        try {
            getScanner(context)?.stopScan(buildPendingIntent(context))
            SafeLogger.i(TAG, "Background BLE scan stopped")
        } catch (e: SecurityException) {
            SafeLogger.w(TAG, "BLE scan stop skipped because Bluetooth permission was revoked")
        }
    }

    private fun hasBluetoothPermissions(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private fun getScanner(context: Context): BluetoothLeScanner? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = bm?.adapter ?: return null
        if (!adapter.isEnabled) {
            SafeLogger.w(TAG, "Bluetooth is off; BLE scan not started")
            return null
        }
        return adapter.bluetoothLeScanner
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BleScanReceiver::class.java).apply {
            action = ACTION_BLE_SCAN_RESULT
        }
        return PendingIntent.getBroadcast(
            context,
            SCAN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}

/**
 * Receives BLE scan results in the background (even when the app is killed).
 * When a PC lock-screen beacon is detected, starts [BluetoothUnlockService]
 * which immediately establishes the RFCOMM connection and shows the unlock notification.
 */
class BleScanReceiver : BroadcastReceiver() {
    private val tag = "BleScanReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val results: List<ScanResult> =
            intent.getParcelableArrayListExtra(android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
                ?: return

        if (results.isEmpty()) return

        SafeLogger.i(tag, "BLE beacon detected from PC (${results.size} result(s)) — starting unlock service")

        // Start (or wake) the unlock service so it connects via RFCOMM immediately
        val serviceIntent = Intent(context, BluetoothUnlockService::class.java).apply {
            action = BluetoothUnlockService.ACTION_START_LISTENING
        }
        context.startForegroundService(serviceIntent)
    }
}
