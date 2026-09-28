package com.windowslockpin.companion.service

import android.app.Notification
import com.windowslockpin.companion.storage.DeviceDetailsStore
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.windowslockpin.companion.R
import com.windowslockpin.companion.bluetooth.BluetoothRfcommClient
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.storage.EncryptedFilePairedDeviceStore
import com.windowslockpin.companion.core.storage.PairedDeviceStore
import com.windowslockpin.companion.core.unlock.UnlockCoordinator
import com.windowslockpin.companion.security.AndroidKeystoreRecordCipher
import com.windowslockpin.companion.storage.SharedPreferencesDeviceIdProvider
import com.windowslockpin.companion.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException

class BluetoothUnlockService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isListening: Boolean = false
    private lateinit var pairedDeviceStore: PairedDeviceStore
    private lateinit var deviceIdProvider: SharedPreferencesDeviceIdProvider
    private lateinit var stateMachine: CompanionStateMachine
    private lateinit var coordinator: UnlockCoordinator
    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreate() {
        super.onCreate()
        SafeLogger.i(TAG, "BluetoothUnlockService onCreate")
        createNotificationChannels()

        deviceIdProvider = SharedPreferencesDeviceIdProvider(this)
        pairedDeviceStore = try {
            EncryptedFilePairedDeviceStore(
                storageDir = File(filesDir, "paired_devices_v1"),
                cipher = AndroidKeystoreRecordCipher()
            )
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to initialize paired store in service", e)
            stopSelf()
            return
        }

        val initialRecords = pairedDeviceStore.getPairedPcs().associateBy { it.pcId.value }
        val candidateStateMachine = CompanionStateMachine(initialPairedPcs = initialRecords)
        val candidateCoordinator = UnlockCoordinator(deviceIdProvider, candidateStateMachine)
        val shared = UnlockSessionManager.initializeIfNeeded(candidateCoordinator, candidateStateMachine)
        this.coordinator = shared.coordinator
        this.stateMachine = shared.stateMachine

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        SafeLogger.i(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_STOP_LISTENING -> {
                serviceScope.launch {
                    try {
                        stopListeningAndCleanup()
                    } catch (e: Exception) {
                        SafeLogger.w(TAG, "Error in stopListeningAndCleanup: ${e.message}")
                    } finally {
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL_REQUEST -> {
                serviceScope.launch {
                    SafeLogger.i(TAG, "User clicked cancel action from notification")
                    dismissHeadsUpNotification()
                    UnlockSessionManager.cancelActiveSession(
                        reasonCode = CancelMessage.REASON_USER_CANCELLED,
                        reasonText = "User cancelled via notification"
                    )
                }
                return START_STICKY
            }
            else -> {
                startForegroundServiceInternal()
                startListeningLoop()
                return START_STICKY
            }
        }
    }

    private fun startForegroundServiceInternal() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun startListeningLoop() {
        if (isListening) return
        isListening = true

        serviceScope.launch {
            SafeLogger.i(TAG, "Starting persistent Bluetooth unlock listener loop")
            while (isActive && isListening) {
                val pairedPcs = pairedDeviceStore.getPairedPcs()
                if (pairedPcs.isEmpty()) {
                    SafeLogger.d(TAG, "No paired PCs available; idling...")
                    delay(PAIRED_IDLE_DELAY_MS)
                    continue
                }

                val adapter = bluetoothAdapter
                if (adapter == null || !adapter.isEnabled) {
                    SafeLogger.w(TAG, "Bluetooth disabled; waiting to retry...")
                    delay(BLUETOOTH_DISABLED_DELAY_MS)
                    continue
                }

                // Establish a persistent connection for each paired PC and hold it.
                // listenForPcChallenge now loops internally until the PC disconnects.
                for (record in pairedPcs) {
                    if (!isActive || !isListening) break
                    listenForPcChallenge(record)
                }

                // Short reconnect guard: only reached when the PC closed the connection.
                delay(SWEEP_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun listenForPcChallenge(record: PairedPcRecord) {
        val client = BluetoothRfcommClient(bluetoothAdapter)
        var connection: com.windowslockpin.companion.core.transport.TransportConnection? = null
        try {
            SafeLogger.d(TAG, "Attempting connection to '${record.pcName}' (${record.bluetoothMac})...")
            connection = withTimeout(CONNECT_TIMEOUT_MS) {
                client.connect(record.bluetoothMac)
            }
            SafeLogger.i(TAG, "Connected to '${record.pcName}'. Holding connection and awaiting challenges...")

            // ── Persistent keep-alive loop ────────────────────────────────────────
            // Stay connected and handle any number of UNLOCK_CHALLENGE frames.
            // Only exit when the socket closes (IOException) or the service stops.
            while (currentCoroutineContext().isActive && isListening && connection.isConnected) {

                // Blocking read — returns only when a frame arrives or the socket closes.
                val frame = connection.receiveFrame()

                when (frame.header.messageType) {
                    ProtocolConstants.MSG_PONG -> {
                        try { DeviceDetailsStore(this).accept(record,frame.payload) }
                        catch(_:Exception) { SafeLogger.w(TAG,"Ignoring invalid optional device metadata") }
                    }
                    ProtocolConstants.MSG_UNLOCK_CHALLENGE -> {
                        val challenge = ProtocolMessage.decode(frame) as UnlockChallenge
                        SafeLogger.i(TAG, "Received challenge from '${record.pcName}', validating...")

                        val transcriptResult = coordinator.processIncomingChallenge(
                            requestId = frame.header.requestId,
                            challenge = challenge,
                            expectedPc = record
                        )

                        if (transcriptResult is ProtocolResult.Failure) {
                            SafeLogger.w(TAG, "Challenge rejected: ${transcriptResult.message}")
                            coordinator.cancel(connection, frame.header.requestId,
                                CancelMessage.REASON_SYSTEM_CANCELLED, transcriptResult.message)
                            // Stay connected — PC may send another challenge later.
                            continue
                        }

                        val transcript = (transcriptResult as ProtocolResult.Success).value

                        val session = ActiveUnlockSession(
                            pcRecord = record,
                            requestId = frame.header.requestId,
                            challenge = challenge,
                            connection = connection,
                            transcript = transcript
                        )

                        val registered = UnlockSessionManager.registerSession(session)
                        if (!registered) {
                            SafeLogger.w(TAG, "Active session already in progress; rejecting")
                            coordinator.cancel(connection, frame.header.requestId,
                                CancelMessage.REASON_SYSTEM_CANCELLED, "Concurrent request rejected")
                            continue
                        }

                        // Only show heads-up floating notification banner when the user is outside the app.
                        // If the user is already inside the app, the in-app card & prompt handles it directly.
                        if (!UnlockSessionManager.isAppInForeground) {
                            showHeadsUpNotification(record, challenge, frame.header.requestId)
                        } else {
                            SafeLogger.i(TAG, "User is inside the app; suppressing heads-up floating banner")
                        }

                        // TTL timeout watcher
                        val timeoutJob = serviceScope.launch {
                            val ttlWaitMs = session.remainingTimeMs()
                            delay(ttlWaitMs)
                            if (UnlockSessionManager.activeSession.value == session) {
                                SafeLogger.w(TAG, "Challenge TTL expired; canceling")
                                dismissHeadsUpNotification()
                                UnlockSessionManager.cancelActiveSession(
                                    reasonCode = CancelMessage.REASON_TIMEOUT,
                                    reasonText = "Challenge TTL expired",
                                    expectedRequestId = session.requestId
                                )
                            }
                        }

                        // Wait for user action or cancellation
                        while (currentCoroutineContext().isActive &&
                            UnlockSessionManager.activeSession.value == session) {
                            if (!connection.isConnected && !session.isSigned) {
                                // PC superseded the challenge: do not wait for its TTL.
                                UnlockSessionManager.clearSession(session)
                                break
                            }
                            delay(250L)
                        }

                        timeoutJob.cancel()
                        dismissHeadsUpNotification()
                        // Loop back — stay connected for the next challenge
                    }

                    ProtocolConstants.MSG_CANCEL -> {
                        // PC explicitly asked us to stop; close and reconnect fresh.
                        SafeLogger.i(TAG, "Received Cancel from PC; closing connection")
                        dismissHeadsUpNotification()
                        UnlockSessionManager.cancelActiveSession(
                            reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
                            reasonText = "PC sent Cancel"
                        )
                        connection.close()
                        return
                    }

                    else -> {
                        SafeLogger.w(TAG, "Unexpected message type: 0x%02X".format(frame.header.messageType))
                        // Ignore unknown frames; stay connected.
                    }
                }
            }

        } catch (e: TimeoutCancellationException) {
            SafeLogger.d(TAG, "Connection attempt to '${record.pcName}' timed out (not at lock screen)")
            try { connection?.close() } catch (_: Exception) {}
        } catch (e: IOException) {
            SafeLogger.d(TAG, "RFCOMM connection closed or failed: ${e.message}")
            try { connection?.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Error in listenForPcChallenge: ${e.message}")
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    private fun showHeadsUpNotification(record: PairedPcRecord, challenge: UnlockChallenge, requestId: RequestId) {
        val openRequestIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_UNLOCK_REQUEST
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_REQUEST_ID, requestId.value)
        }
        val unlockIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_CONFIRM_UNLOCK
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_REQUEST_ID, requestId.value)
        }
        val unlockPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_UNLOCK,
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, BluetoothUnlockService::class.java).apply {
            action = ACTION_CANCEL_REQUEST
            putExtra(EXTRA_REQUEST_ID, requestId.value)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            openRequestIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val headsUpNotification = NotificationCompat.Builder(this, CHANNEL_UNLOCK_HEADS_UP)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(getColor(R.color.primary))
            .setContentTitle(getString(R.string.notification_request_title))
            .setContentText(getString(R.string.notification_request_text, DeviceDetailsStore(this).displayName(record)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.unlock_request_card_user, challenge.userDisplayName)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 250, 200, 250))
            .setOngoing(false) // CRITICAL: Ongoing notifications are suppressed from floating by Android & MIUI!
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .addAction(
                0,
                getString(R.string.notification_action_unlock),
                unlockPendingIntent
            )
            .addAction(
                0,
                getString(R.string.notification_action_cancel),
                cancelPendingIntent
            )
            .build()

        // MIUI / HyperOS heads-up floating banner support via reflection & extras
        try {
            val extraNotification = headsUpNotification.javaClass.getField("extraNotification").get(headsUpNotification)
            val setFloatTime = extraNotification.javaClass.getMethod("setFloatTime", Int::class.javaPrimitiveType)
            setFloatTime.invoke(extraNotification, 10000)
        } catch (_: Exception) {}
        try {
            headsUpNotification.extras.putBoolean("miui.float", true)
            headsUpNotification.extras.putBoolean("miui.show_floating", true)
            headsUpNotification.extras.putBoolean("miui.enable_float", true)
        } catch (_: Exception) {}

        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.notify(NOTIFICATION_ID_CHALLENGE, headsUpNotification)
        } catch (e: SecurityException) {
            SafeLogger.w(TAG, "Notification permission missing when showing heads-up notification")
        }
    }

    private fun dismissHeadsUpNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.cancel(NOTIFICATION_ID_CHALLENGE)
        } catch (_: Exception) {}
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_APP
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_LISTENER_SERVICE)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(getColor(R.color.primary))
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Clean up legacy channel IDs
            try {
                notificationManager.deleteNotificationChannel("wslp_unlock_heads_up_channel")
                notificationManager.deleteNotificationChannel("wslp_unlock_heads_up_channel_v2")
            } catch (_: Exception) {}

            val serviceChannel = NotificationChannel(
                CHANNEL_LISTENER_SERVICE,
                getString(R.string.notification_channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                .build()

            val unlockChannel = NotificationChannel(
                CHANNEL_UNLOCK_HEADS_UP,
                getString(R.string.notification_channel_request_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_request_desc)
                setSound(soundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 200, 250)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = Color.BLUE
                setBypassDnd(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(unlockChannel)
        }
    }

    suspend fun stopListeningAndCleanup() {
        isListening = false
        dismissHeadsUpNotification()
        UnlockSessionManager.cancelActiveSession(
            reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
            reasonText = "Service stopped"
        )
    }

    override fun onDestroy() {
        SafeLogger.i(TAG, "BluetoothUnlockService onDestroy")
        isListening = false
        dismissHeadsUpNotification()
        runBlocking {
            try {
                withTimeout(1500L) {
                    stopListeningAndCleanup()
                }
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Cleanup in onDestroy timed out or failed: ${e.message}")
            }
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BluetoothUnlockService"
        const val CHANNEL_LISTENER_SERVICE = "wslp_listener_service_channel"
        const val CHANNEL_UNLOCK_HEADS_UP = "wslp_unlock_heads_up_v4"

        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_CHALLENGE = 2001

        const val ACTION_START_LISTENING = "com.windowslockpin.companion.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.windowslockpin.companion.ACTION_STOP_LISTENING"
        const val ACTION_CANCEL_REQUEST = "com.windowslockpin.companion.ACTION_CANCEL_REQUEST"
        const val ACTION_CONFIRM_UNLOCK = "com.windowslockpin.companion.ACTION_CONFIRM_UNLOCK"
        const val ACTION_OPEN_UNLOCK_REQUEST = "com.windowslockpin.companion.ACTION_OPEN_UNLOCK_REQUEST"
        const val ACTION_OPEN_APP = "com.windowslockpin.companion.ACTION_OPEN_APP"

        const val EXTRA_REQUEST_ID = "extra_request_id"

        private const val REQUEST_CODE_UNLOCK = 101
        private const val REQUEST_CODE_CANCEL = 102
        private const val REQUEST_CODE_CONTENT = 103

        private const val CONNECT_TIMEOUT_MS = 8_000L         // connect attempt timeout
        private const val SWEEP_RETRY_DELAY_MS = 2_000L       // delay after disconnect before reconnect
        private const val PAIRED_IDLE_DELAY_MS = 15_000L
        private const val BLUETOOTH_DISABLED_DELAY_MS = 10_000L
    }
}
