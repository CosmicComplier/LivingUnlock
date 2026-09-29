package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.crypto.FakeSecretStore
import com.windowslockpin.companion.core.crypto.PairingConfirmation
import com.windowslockpin.companion.core.crypto.Sec1P256
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.pairing.PairingCoordinator
import com.windowslockpin.companion.core.pairing.PairingResult
import com.windowslockpin.companion.core.statemachine.CompanionState
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.storage.FixedDeviceIdProvider
import com.windowslockpin.companion.core.storage.FakePairedDeviceStore
import com.windowslockpin.companion.core.transport.FakeTransportConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class PairingCoordinatorTest {

    private val testDeviceId = DeviceId("android-test-device-uuid-12345678")
    private val testDeviceName = "Pixel 8 Pro"
    private val testPcId = PcId.fromString("0123456789abcdef0123456789abcdef")
    private val testMac = BluetoothMacAddress.parse("AA:BB:CC:DD:EE:FF")
    private val fakeSecretStore = FakeSecretStore()
    private val fakePairedStore = FakePairedDeviceStore()
    private lateinit var stateMachine: CompanionStateMachine

    private lateinit var serverKeyPair: java.security.KeyPair
    private lateinit var serverPublicKeySec1: ByteArray

    @Before
    fun setUp() {
        fakePairedStore.clear()
        stateMachine = CompanionStateMachine(timeProvider = { 1700000000_000L })
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        serverKeyPair = kpg.generateKeyPair()
        serverPublicKeySec1 = Sec1P256.encodePublicKey(serverKeyPair.public as ECPublicKey)
    }

    private fun createValidUri(expOffsetSec: Long = 100L): PairingUri {
        val rawToken = ByteArray(32) { (it + 5).toByte() }
        val token = PairingToken(rawToken)
        val nowSec = 1700000000L
        return PairingUri(
            version = 1,
            pcId = testPcId,
            pcName = "Desktop-PC",
            bluetoothMac = testMac,
            pairingToken = token,
            expiryEpochSec = nowSec + expOffsetSec
        )
    }

    @Test
    fun testPairingSuccessFlow() = runBlocking {
        val (phoneConn, pcConn) = FakeTransportConnection.createPair()
        val transportClient = com.windowslockpin.companion.core.transport.FakeTransportClient { phoneConn }

        val coordinator = PairingCoordinator(
            deviceIdProvider = FixedDeviceIdProvider(testDeviceId),
            deviceNameProvider = { testDeviceName },
            secretStore = fakeSecretStore,
            transportClient = transportClient,
            pairedDeviceStore = fakePairedStore,
            stateMachine = stateMachine,
            timeProvider = { 1700000000_000L }
        )

        val uri = createValidUri(expOffsetSec = 100L)
        // Keep a copy of the token before coordinator runs, as coordinator zeroizes uri.pairingToken
        val originalToken = uri.pairingToken.bytes.copyOf()

        val pairJob = async {
            coordinator.pair(uri)
        }

        // Host side receives PAIR_REQUEST
        val reqFrame = pcConn.receiveFrame()
        assertEquals(ProtocolConstants.MSG_PAIR_REQUEST, reqFrame.header.messageType)
        val pairReq = ProtocolMessage.decode(reqFrame) as PairRequest
        assertEquals(testDeviceId, pairReq.deviceId)
        assertEquals(testDeviceName, pairReq.deviceName)
        assertArrayEquals(originalToken, pairReq.pairingToken.bytes)
        assertTrue(Sec1P256.isValidSec1P256(pairReq.clientPublicKey))

        // Host computes confirmation tag
        val salt = PairingConfirmation.derivePairingSalt(testPcId, testDeviceId)
        val kPair = PairingConfirmation.deriveKPair(salt, PairingToken(originalToken))
        val tag = PairingConfirmation.computeConfirmationTag(
            kPair = kPair,
            pcId = testPcId,
            deviceId = testDeviceId,
            clientPublicKey = pairReq.clientPublicKey,
            serverPublicKey = serverPublicKeySec1
        )

        // Host replies with PAIR_RESPONSE
        val resp = PairResponse(
            statusCode = 0x0000,
            serverPublicKey = serverPublicKeySec1,
            confirmationTag = tag
        )
        pcConn.sendFrame(resp.toFrame(reqFrame.header.requestId))

        val result = pairJob.await()
        assertTrue("Pairing should succeed, got $result", result is PairingResult.Success)
        val success = result as PairingResult.Success
        assertEquals(testPcId, success.record.pcId)
        assertEquals("Desktop-PC", success.record.pcName)
        assertEquals(testMac, success.record.bluetoothMac)
        assertArrayEquals(serverPublicKeySec1, success.record.serverPublicKey)

        // Verify storage updated
        val stored = fakePairedStore.getPairedPc(testPcId)
        assertNotNull(stored)
        assertEquals(stored, success.record)

        // Verify state machine updated to PairedIdle
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)

        // Verify pairing token was zeroized
        assertTrue(uri.pairingToken.bytes.all { it == 0.toByte() })

        // Verify connection closed
        assertFalse(phoneConn.isConnected)
    }

    @Test
    fun testPairingQrExpired() = runBlocking {
        val (phoneConn, _) = FakeTransportConnection.createPair()
        val transportClient = com.windowslockpin.companion.core.transport.FakeTransportClient { phoneConn }

        val coordinator = PairingCoordinator(
            deviceIdProvider = FixedDeviceIdProvider(testDeviceId),
            deviceNameProvider = { testDeviceName },
            secretStore = fakeSecretStore,
            transportClient = transportClient,
            pairedDeviceStore = fakePairedStore,
            stateMachine = stateMachine,
            timeProvider = { 1700000000_000L }
        )

        val uri = createValidUri(expOffsetSec = -10L) // already expired
        val result = coordinator.pair(uri)

        assertTrue(result is PairingResult.Failure)
        val fail = result as PairingResult.Failure
        assertEquals(ProtocolErrorCode.EXPIRED, fail.errorCode)
        assertTrue(fakePairedStore.getPairedPcs().isEmpty())
    }

    @Test
    fun testPairingConnectionFailure() = runBlocking {
        val failingClient = object : com.windowslockpin.companion.core.transport.TransportClient {
            override suspend fun connect(macAddress: BluetoothMacAddress): com.windowslockpin.companion.core.transport.TransportConnection {
                throw IOException("Bluetooth connection refused")
            }
        }

        val coordinator = PairingCoordinator(
            deviceIdProvider = FixedDeviceIdProvider(testDeviceId),
            deviceNameProvider = { testDeviceName },
            secretStore = fakeSecretStore,
            transportClient = failingClient,
            pairedDeviceStore = fakePairedStore,
            stateMachine = stateMachine,
            timeProvider = { 1700000000_000L }
        )

        val uri = createValidUri(expOffsetSec = 100L)
        val result = coordinator.pair(uri)

        assertTrue(result is PairingResult.Failure)
        val fail = result as PairingResult.Failure
        assertEquals(ProtocolErrorCode.UNEXPECTED_STATE, fail.errorCode)
        assertTrue(stateMachine.currentState is CompanionState.Unpaired)
    }

    @Test
    fun testPairingHostRejectedStatus() = runBlocking {
        val (phoneConn, pcConn) = FakeTransportConnection.createPair()
        val transportClient = com.windowslockpin.companion.core.transport.FakeTransportClient { phoneConn }

        val coordinator = PairingCoordinator(
            deviceIdProvider = FixedDeviceIdProvider(testDeviceId),
            deviceNameProvider = { testDeviceName },
            secretStore = fakeSecretStore,
            transportClient = transportClient,
            pairedDeviceStore = fakePairedStore,
            stateMachine = stateMachine,
            timeProvider = { 1700000000_000L }
        )

        val uri = createValidUri(expOffsetSec = 100L)
        val pairJob = async { coordinator.pair(uri) }

        val reqFrame = pcConn.receiveFrame()
        val rejectResp = PairResponse(
            statusCode = 0x0001,
            serverPublicKey = ByteArray(0),
            confirmationTag = ByteArray(0)
        )
        pcConn.sendFrame(rejectResp.toFrame(reqFrame.header.requestId))

        val result = pairJob.await()
        assertTrue(result is PairingResult.Failure)
        val fail = result as PairingResult.Failure
        assertEquals(ProtocolErrorCode.CRYPTO_FAILURE, fail.errorCode)
        assertTrue(fakePairedStore.getPairedPcs().isEmpty())
    }

    @Test
    fun testPairingInvalidServerKeyRejected() = runBlocking {
        val (phoneConn, pcConn) = FakeTransportConnection.createPair()
        val transportClient = com.windowslockpin.companion.core.transport.FakeTransportClient { phoneConn }

        val coordinator = PairingCoordinator(
            deviceIdProvider = FixedDeviceIdProvider(testDeviceId),
            deviceNameProvider = { testDeviceName },
            secretStore = fakeSecretStore,
            transportClient = transportClient,
            pairedDeviceStore = fakePairedStore,
            stateMachine = stateMachine,
            timeProvider = { 1700000000_000L }
        )

        val uri = createValidUri(expOffsetSec = 100L)
        val pairJob = async { coordinator.pair(uri) }

        val reqFrame = pcConn.receiveFrame()
        // Invalid public key (65 bytes but does not start with 0x04)
        val badKey = ByteArray(65) { 0x02 }
        val resp = PairResponse(
            statusCode = 0x0000,
            serverPublicKey = badKey,
            confirmationTag = ByteArray(32)
        )
        pcConn.sendFrame(resp.toFrame(reqFrame.header.requestId))

        val result = pairJob.await()
        assertTrue(result is PairingResult.Failure)
        val fail = result as PairingResult.Failure
        assertEquals(ProtocolErrorCode.MALFORMED_PAYLOAD, fail.errorCode)
    }

    @Test
    fun testPairingConfirmationTagMismatch() = runBlocking {
        val (phoneConn, pcConn) = FakeTransportConnection.createPair()
        val transportClient = com.windowslockpin.companion.core.transport.FakeTransportClient { phoneConn }

        val coordinator = PairingCoordinator(
            deviceIdProvider = FixedDeviceIdProvider(testDeviceId),
            deviceNameProvider = { testDeviceName },
            secretStore = fakeSecretStore,
            transportClient = transportClient,
            pairedDeviceStore = fakePairedStore,
            stateMachine = stateMachine,
            timeProvider = { 1700000000_000L }
        )

        val uri = createValidUri(expOffsetSec = 100L)
        val pairJob = async { coordinator.pair(uri) }

        val reqFrame = pcConn.receiveFrame()
        // Wrong confirmation tag
        val wrongTag = ByteArray(32) { 0xFF.toByte() }
        val resp = PairResponse(
            statusCode = 0x0000,
            serverPublicKey = serverPublicKeySec1,
            confirmationTag = wrongTag
        )
        pcConn.sendFrame(resp.toFrame(reqFrame.header.requestId))

        val result = pairJob.await()
        assertTrue(result is PairingResult.Failure)
        val fail = result as PairingResult.Failure
        assertEquals(ProtocolErrorCode.CRYPTO_FAILURE, fail.errorCode)
        assertTrue(fakePairedStore.getPairedPcs().isEmpty())
    }

    @Test
    fun testPairingRequestIdMismatch() = runBlocking {
        val (phoneConn, pcConn) = FakeTransportConnection.createPair()
        val transportClient = com.windowslockpin.companion.core.transport.FakeTransportClient { phoneConn }

        val coordinator = PairingCoordinator(
            deviceIdProvider = FixedDeviceIdProvider(testDeviceId),
            deviceNameProvider = { testDeviceName },
            secretStore = fakeSecretStore,
            transportClient = transportClient,
            pairedDeviceStore = fakePairedStore,
            stateMachine = stateMachine,
            timeProvider = { 1700000000_000L }
        )

        val uri = createValidUri(expOffsetSec = 100L)
        val pairJob = async { coordinator.pair(uri) }

        val reqFrame = pcConn.receiveFrame()
        val resp = PairResponse(
            statusCode = 0x0000,
            serverPublicKey = serverPublicKeySec1,
            confirmationTag = ByteArray(32)
        )
        // Reply with different request ID
        val wrongRequestId = RequestId(reqFrame.header.requestId.value + 1)
        pcConn.sendFrame(resp.toFrame(wrongRequestId))

        val result = pairJob.await()
        assertTrue(result is PairingResult.Failure)
        val fail = result as PairingResult.Failure
        assertEquals(ProtocolErrorCode.DEVICE_MISMATCH, fail.errorCode)
    }
}
