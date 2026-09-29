package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.*
import com.windowslockpin.companion.core.storage.DeviceIdProvider
import com.windowslockpin.companion.core.transport.FakeTransportConnection
import com.windowslockpin.companion.core.unlock.UnlockCoordinator
import com.windowslockpin.companion.core.unlock.UnlockExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UnlockCoordinatorTest {

    private val pcId = PcId.fromString("abcdef0123456789abcdef0123456789")
    private val pcName = "Host-Office"
    private val btMac = BluetoothMacAddress.parse("11:22:33:44:55:66")
    private val deviceId = DeviceId("android-test-device-uuid")
    private var currentTimeMs: Long = 1700000000000L

    private lateinit var stateMachine: CompanionStateMachine
    private lateinit var pairedRecord: PairedPcRecord
    private lateinit var coordinator: UnlockCoordinator

    @Before
    fun setUp() {
        pairedRecord = PairedPcRecord(
            pcId = pcId,
            pcName = pcName,
            bluetoothMac = btMac,
            serverPublicKey = ByteArray(65) { 0x04 },
            kPair = ByteArray(32),
            pairedTimestampMs = currentTimeMs
        )
        stateMachine = CompanionStateMachine(
            initialPairedPcs = mapOf(pcId.value to pairedRecord),
            timeProvider = { currentTimeMs }
        )
        val deviceIdProvider = object : DeviceIdProvider {
            override fun getDeviceId(): DeviceId = deviceId
        }
        coordinator = UnlockCoordinator(deviceIdProvider, stateMachine)
    }

    private fun createChallenge(
        targetPcId: PcId = pcId,
        nonce: ChallengeNonce = ChallengeNonce.generate(32),
        ts: Long = currentTimeMs,
        ttl: Long = 30000L
    ): UnlockChallenge {
        return UnlockChallenge(
            pcId = targetPcId,
            challengeNonce = nonce,
            timestampMs = ts,
            ttlMs = ttl,
            userDisplayName = "Alice"
        )
    }

    @Test
    fun testSuccessfulUnlockFlow() = runBlocking {
        val (clientTransport, serverTransport) = FakeTransportConnection.createPair()
        val reqId = RequestId.generate()
        val nonce = ChallengeNonce.generate(32)
        val challenge = createChallenge(nonce = nonce)

        // 1. Process valid incoming challenge
        val transcriptResult = coordinator.processIncomingChallenge(reqId, challenge, pairedRecord)
        assertTrue(transcriptResult is ProtocolResult.Success)
        val transcript = (transcriptResult as ProtocolResult.Success).value
        assertTrue(transcript.isNotEmpty())
        assertTrue(stateMachine.currentState is CompanionState.BiometricPrompting)

        // 2. Mock signing and sending response
        val mockDerSig = ByteArray(70) { 0x30 }
        val sendResult = coordinator.sendUnlockResponse(clientTransport, reqId, nonce, mockDerSig)
        assertTrue(sendResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.AwaitingUnlockResult)

        // Verify server received UNLOCK_RESPONSE frame
        val responseFrame = serverTransport.receiveFrame()
        assertEquals(ProtocolConstants.MSG_UNLOCK_RESPONSE, responseFrame.header.messageType)
        assertEquals(reqId, responseFrame.header.requestId)
        val unlockResp = ProtocolMessage.decode(responseFrame) as UnlockResponse
        assertEquals(UnlockResponse.STATUS_SUCCESS, unlockResp.statusCode)
        assertArrayEquals(mockDerSig, unlockResp.authSignature)

        // 3. Server sends UNLOCK_RESULT with status ACCEPTED (0)
        val unlockResultMsg = UnlockResult(UnlockResult.STATUS_ACCEPTED, "Windows accepted unlock request")
        serverTransport.sendFrame(unlockResultMsg.toFrame(reqId))

        // 4. Client awaits and processes UNLOCK_RESULT
        val finalResult = coordinator.awaitUnlockResult(clientTransport, reqId, timeoutMs = 2000L)
        assertTrue(finalResult is UnlockExecutionResult.Success)
        val success = finalResult as UnlockExecutionResult.Success
        assertEquals(UnlockResult.STATUS_ACCEPTED, success.result.statusCode)
        assertEquals("Windows accepted unlock request", success.result.message)
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)
    }

    @Test
    fun testUnlockResultRejected() = runBlocking {
        val (clientTransport, serverTransport) = FakeTransportConnection.createPair()
        val reqId = RequestId.generate()
        val nonce = ChallengeNonce.generate(32)
        val challenge = createChallenge(nonce = nonce)

        coordinator.processIncomingChallenge(reqId, challenge, pairedRecord)
        coordinator.sendUnlockResponse(clientTransport, reqId, nonce, ByteArray(70) { 0x30 })

        // Server sends UNLOCK_RESULT with status REJECTED (1)
        val resultMsg = UnlockResult(UnlockResult.STATUS_REJECTED, "Invalid signature")
        serverTransport.sendFrame(resultMsg.toFrame(reqId))

        val finalResult = coordinator.awaitUnlockResult(clientTransport, reqId, timeoutMs = 2000L)
        assertTrue(finalResult is UnlockExecutionResult.Rejected)
        val rejected = finalResult as UnlockExecutionResult.Rejected
        assertEquals(UnlockResult.STATUS_REJECTED, rejected.result.statusCode)
        assertEquals("Invalid signature", rejected.result.message)
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)
    }

    @Test
    fun testUnlockResultHostError() = runBlocking {
        val (clientTransport, serverTransport) = FakeTransportConnection.createPair()
        val reqId = RequestId.generate()
        val nonce = ChallengeNonce.generate(32)
        val challenge = createChallenge(nonce = nonce)

        coordinator.processIncomingChallenge(reqId, challenge, pairedRecord)
        coordinator.sendUnlockResponse(clientTransport, reqId, nonce, ByteArray(70) { 0x30 })

        // Server sends UNLOCK_RESULT with status ERROR (2)
        val resultMsg = UnlockResult(UnlockResult.STATUS_ERROR, "Windows Logon subsystem error")
        serverTransport.sendFrame(resultMsg.toFrame(reqId))

        val finalResult = coordinator.awaitUnlockResult(clientTransport, reqId, timeoutMs = 2000L)
        assertTrue(finalResult is UnlockExecutionResult.HostError)
        val err = finalResult as UnlockExecutionResult.HostError
        assertEquals(UnlockResult.STATUS_ERROR, err.result.statusCode)
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)
    }

    @Test
    fun testRejectUnknownPcId() {
        val unknownPcId = PcId.fromString("99999999999999999999999999999999")
        val reqId = RequestId.generate()
        val challenge = createChallenge(targetPcId = unknownPcId)

        val result = coordinator.processIncomingChallenge(reqId, challenge, pairedRecord)
        assertTrue(result.isFailure)
        val fail = result as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.DEVICE_MISMATCH, fail.errorCode)
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)
    }

    @Test
    fun testRejectConnectedPcMismatch() {
        val otherConnectedPc = PairedPcRecord(
            pcId = PcId.fromString("77777777777777777777777777777777"),
            pcName = "Other-PC",
            bluetoothMac = btMac,
            serverPublicKey = ByteArray(65) { 0x04 },
            kPair = ByteArray(32),
            pairedTimestampMs = currentTimeMs
        )
        val reqId = RequestId.generate()
        val challenge = createChallenge(targetPcId = pcId) // PC is pcId, but expected is otherConnectedPc

        val result = coordinator.processIncomingChallenge(reqId, challenge, otherConnectedPc)
        assertTrue(result.isFailure)
        val fail = result as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.DEVICE_MISMATCH, fail.errorCode)
    }

    @Test
    fun testRejectExpiredChallenge() {
        val reqId = RequestId.generate()
        // Expired timestamp (exceeds clock skew)
        val challenge = createChallenge(ts = currentTimeMs - 70000L)

        val result = coordinator.processIncomingChallenge(reqId, challenge, pairedRecord)
        assertTrue(result.isFailure)
        val fail = result as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.EXPIRED, fail.errorCode)
    }

    @Test
    fun testRejectReplayedNonce() {
        val reqId1 = RequestId.generate()
        val nonce = ChallengeNonce.generate(32)
        val challenge1 = createChallenge(nonce = nonce)

        val r1 = coordinator.processIncomingChallenge(reqId1, challenge1, pairedRecord)
        assertTrue(r1.isSuccess)

        // Cancel first request to reset state machine to PairedIdle
        runBlocking { coordinator.cancel(null, null, reasonText = "Reset for test") }
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)

        // Second challenge reusing the same nonce must be rejected
        val reqId2 = RequestId.generate()
        val challenge2 = createChallenge(nonce = nonce)
        val r2 = coordinator.processIncomingChallenge(reqId2, challenge2, pairedRecord)
        assertTrue(r2.isFailure)
        val fail = r2 as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.REPLAY_DETECTED, fail.errorCode)
    }

    @Test
    fun testUserCancellationSendsCancelFrame() = runBlocking {
        val (clientTransport, serverTransport) = FakeTransportConnection.createPair()
        val reqId = RequestId.generate()
        val challenge = createChallenge()

        coordinator.processIncomingChallenge(reqId, challenge, pairedRecord)
        assertTrue(stateMachine.currentState is CompanionState.BiometricPrompting)

        coordinator.cancel(
            connection = clientTransport,
            requestId = reqId,
            reasonCode = CancelMessage.REASON_USER_CANCELLED,
            reasonText = "User dismissed"
        )

        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)

        val cancelFrame = serverTransport.receiveFrame()
        assertEquals(ProtocolConstants.MSG_CANCEL, cancelFrame.header.messageType)
        val cancelMsg = ProtocolMessage.decode(cancelFrame) as CancelMessage
        assertEquals(CancelMessage.REASON_USER_CANCELLED, cancelMsg.reasonCode)
    }
}
