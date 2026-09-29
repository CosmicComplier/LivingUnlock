package com.windowslockpin.companion.core.unlock

import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.storage.DeviceIdProvider
import com.windowslockpin.companion.core.transport.TransportConnection
import kotlinx.coroutines.withTimeout

sealed class UnlockExecutionResult {
    data class Success(val result: UnlockResult) : UnlockExecutionResult()
    data class Rejected(val result: UnlockResult) : UnlockExecutionResult()
    data class HostError(val result: UnlockResult) : UnlockExecutionResult()
    data class Failure(val errorCode: ProtocolErrorCode, val message: String, val cause: Throwable? = null) : UnlockExecutionResult()
    data class Cancelled(val reason: String) : UnlockExecutionResult()
}

class UnlockCoordinator(
    private val deviceIdProvider: DeviceIdProvider,
    private val stateMachine: CompanionStateMachine
) {

    /**
     * Validates an incoming UNLOCK_CHALLENGE frame against the CompanionStateMachine and expected PC.
     * If valid, transitions state to ChallengeReceived, starts biometric state, and constructs
     * the canonical transcript for signing.
     */
    fun processIncomingChallenge(
        requestId: RequestId,
        challenge: UnlockChallenge,
        expectedPc: PairedPcRecord
    ): ProtocolResult<ByteArray> {
        val challengeResult = stateMachine.onUnlockChallengeReceived(
            requestId = requestId,
            challenge = challenge,
            expectedPcId = expectedPc.pcId
        )
        if (challengeResult is ProtocolResult.Failure) {
            return ProtocolResult.Failure(challengeResult.errorCode, challengeResult.message, challengeResult.cause)
        }

        val transcript = CanonicalTranscript.build(
            pcId = challenge.pcId,
            deviceId = deviceIdProvider.getDeviceId(),
            requestId = requestId,
            challengeNonce = challenge.challengeNonce,
            timestampMs = challenge.timestampMs,
            ttlMs = challenge.ttlMs
        )

        val promptResult = stateMachine.onBiometricPromptStarted()
        if (promptResult is ProtocolResult.Failure) {
            transcript.fill(0)
            return ProtocolResult.Failure(promptResult.errorCode, promptResult.message, promptResult.cause)
        }

        return ProtocolResult.Success(transcript)
    }

    /**
     * Sends UNLOCK_RESPONSE with DER signature, transitions state to AwaitingUnlockResult.
     */
    suspend fun sendUnlockResponse(
        connection: TransportConnection,
        requestId: RequestId,
        challengeNonce: ChallengeNonce,
        authSignature: ByteArray
    ): ProtocolResult<Unit> {
        val bioResult = stateMachine.onBiometricPromptCompleted(success = true)
        if (bioResult is ProtocolResult.Failure) {
            return ProtocolResult.Failure(bioResult.errorCode, bioResult.message, bioResult.cause)
        }

        val unlockResponse = UnlockResponse(
            statusCode = UnlockResponse.STATUS_SUCCESS,
            challengeNonce = challengeNonce,
            authSignature = authSignature
        )

        return try {
            connection.sendFrame(unlockResponse.toFrame(requestId))
            stateMachine.onUnlockResponseSent(requestId)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to send UNLOCK_RESPONSE", e)
            stateMachine.onCancelled("Send UNLOCK_RESPONSE failed")
            ProtocolResult.Failure(ProtocolErrorCode.UNEXPECTED_STATE, "Failed to send unlock response: ${e.message}", e)
        }
    }

    /**
     * Waits for UNLOCK_RESULT frame from Windows host with timeout.
     * Completes state machine cycle upon valid result.
     */
    suspend fun awaitUnlockResult(
        connection: TransportConnection,
        requestId: RequestId,
        timeoutMs: Long = DEFAULT_RESULT_TIMEOUT_MS
    ): UnlockExecutionResult {
        return try {
            val frame = withTimeout(timeoutMs) { connection.receiveFrame() }
            if (frame.header.messageType != ProtocolConstants.MSG_UNLOCK_RESULT) {
                SafeLogger.w(TAG, "Expected UNLOCK_RESULT (0x09), got 0x%02X".format(frame.header.messageType))
                stateMachine.onCancelled("Unexpected message type awaiting unlock result")
                return UnlockExecutionResult.Failure(
                    ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE,
                    "Expected UNLOCK_RESULT (0x09), got 0x%02X".format(frame.header.messageType)
                )
            }

            if (frame.header.requestId != requestId) {
                SafeLogger.w(TAG, "Request ID mismatch awaiting UNLOCK_RESULT")
                stateMachine.onCancelled("Request ID mismatch")
                return UnlockExecutionResult.Failure(
                    ProtocolErrorCode.DEVICE_MISMATCH,
                    "Request ID mismatch on UNLOCK_RESULT"
                )
            }

            val resultMsg = try {
                ProtocolMessage.decode(frame) as UnlockResult
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Malformed UNLOCK_RESULT payload: ${e.javaClass.simpleName}")
                stateMachine.onCancelled("Malformed UNLOCK_RESULT")
                return UnlockExecutionResult.Failure(
                    ProtocolErrorCode.MALFORMED_PAYLOAD,
                    "Failed to decode UNLOCK_RESULT: ${e.message}",
                    e
                )
            }

            val smResult = stateMachine.onUnlockResultReceived(requestId, resultMsg)
            if (smResult is ProtocolResult.Failure) {
                stateMachine.onCancelled("State machine rejected result: ${smResult.message}")
                return UnlockExecutionResult.Failure(smResult.errorCode, smResult.message, smResult.cause)
            }

            stateMachine.onUnlockCycleFinished()

            when (resultMsg.statusCode) {
                UnlockResult.STATUS_ACCEPTED -> UnlockExecutionResult.Success(resultMsg)
                UnlockResult.STATUS_REJECTED -> UnlockExecutionResult.Rejected(resultMsg)
                else -> UnlockExecutionResult.HostError(resultMsg)
            }
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Timeout or error awaiting UNLOCK_RESULT: ${e.javaClass.simpleName}")
            stateMachine.onCancelled("Timeout or error awaiting UNLOCK_RESULT")
            UnlockExecutionResult.Failure(
                ProtocolErrorCode.UNEXPECTED_STATE,
                "Timed out or connection dropped while awaiting UNLOCK_RESULT: ${e.message}",
                e
            )
        }
    }

    /**
     * Sends CANCEL frame and resets state machine.
     */
    suspend fun cancel(
        connection: TransportConnection?,
        requestId: RequestId?,
        reasonCode: Byte = CancelMessage.REASON_USER_CANCELLED,
        reasonText: String = "User cancelled"
    ) {
        if (connection != null && requestId != null) {
            try {
                val cancelMsg = CancelMessage(reasonCode)
                connection.sendFrame(cancelMsg.toFrame(requestId))
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Failed to send CANCEL frame: ${e.message}")
            }
        }
        stateMachine.onCancelled(reasonText)
    }

    companion object {
        private const val TAG = "UnlockCoordinator"
        const val DEFAULT_RESULT_TIMEOUT_MS = 15_000L
    }
}
