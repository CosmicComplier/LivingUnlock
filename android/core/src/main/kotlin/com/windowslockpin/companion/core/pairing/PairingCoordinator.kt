package com.windowslockpin.companion.core.pairing

import com.windowslockpin.companion.core.crypto.CryptoEngine
import com.windowslockpin.companion.core.crypto.PairingConfirmation
import com.windowslockpin.companion.core.crypto.Sec1P256
import com.windowslockpin.companion.core.crypto.SecretStore
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.storage.DeviceIdProvider
import com.windowslockpin.companion.core.storage.PairedDeviceStore
import com.windowslockpin.companion.core.transport.TransportClient
import com.windowslockpin.companion.core.transport.TransportConnection
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.withTimeout

sealed class PairingResult {
    data class Success(val record: PairedPcRecord) : PairingResult()
    data class Failure(
        val errorCode: ProtocolErrorCode,
        val message: String,
        val cause: Throwable? = null
    ) : PairingResult()
}

class PairingCoordinator(
    private val deviceIdProvider: DeviceIdProvider,
    private val deviceNameProvider: () -> String,
    private val secretStore: SecretStore,
    private val transportClient: TransportClient,
    private val pairedDeviceStore: PairedDeviceStore,
    private val stateMachine: CompanionStateMachine,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val onPairedConnection: suspend (PairedPcRecord, TransportConnection) -> Unit = { _, _ -> }
) {

    suspend fun pair(uri: PairingUri, keyAlias: String = DEFAULT_KEY_ALIAS): PairingResult {
        var connection: TransportConnection? = null
        var kPair = ByteArray(0)
        try {
            val nowSec = timeProvider() / 1000L
            if (uri.isExpired(nowSec) || uri.expiryEpochSec - nowSec > ProtocolConstants.MAX_QR_EXPIRY_WINDOW_SEC) {
                SafeLogger.w(TAG, "Pairing QR URI is expired or outside the allowed lifetime")
                return PairingResult.Failure(ProtocolErrorCode.EXPIRED, "Pairing QR code is outside the allowed lifetime")
            }
            val qrStateResult = stateMachine.onQrScanned(uri)
            if (qrStateResult is ProtocolResult.Failure)
                return PairingResult.Failure(qrStateResult.errorCode, qrStateResult.message, qrStateResult.cause)

            val deviceId = deviceIdProvider.getDeviceId()
            val deviceName = deviceNameProvider()
            val keyPair = try {
                secretStore.getOrCreateCompanionKeyPair(keyAlias)
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Failed to get or generate companion EC keypair", e)
                stateMachine.onCancelled("Keypair generation error")
                return PairingResult.Failure(ProtocolErrorCode.CRYPTO_FAILURE, "Keystore key generation failed", e)
            }
            val clientPublicKey = try {
                Sec1P256.encodePublicKey(keyPair.public as ECPublicKey)
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Failed to encode client public key as uncompressed SEC1 P-256", e)
                stateMachine.onCancelled("Public key encoding error")
                return PairingResult.Failure(ProtocolErrorCode.CRYPTO_FAILURE, "Failed to encode public key", e)
            }
            val pairingSalt = PairingConfirmation.derivePairingSalt(uri.pcId, deviceId)
            try {
                kPair = PairingConfirmation.deriveKPair(pairingSalt, uri.pairingToken)
            } finally {
                CryptoEngine.zeroize(pairingSalt)
            }

            SafeLogger.i(TAG, "Connecting to host via RFCOMM...")
            connection = try {
                withTimeout(CONNECT_TIMEOUT_MS) { transportClient.connect(uri.bluetoothMac) }
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Bluetooth RFCOMM connection failed")
                stateMachine.onCancelled("RFCOMM connection failed")
                return PairingResult.Failure(ProtocolErrorCode.UNEXPECTED_STATE, "Failed to connect to host over Bluetooth RFCOMM", e)
            }

            val requestId = RequestId.generate()

            val pairReq = PairRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                pairingToken = uri.pairingToken,
                clientPublicKey = clientPublicKey
            )

            stateMachine.onPairRequestSent(requestId)
            SafeLogger.i(TAG, "Sending PAIR_REQUEST to host...")
            connection.sendFrame(pairReq.toFrame(requestId))

            SafeLogger.i(TAG, "Waiting for PAIR_RESPONSE from host...")
            val responseFrame = try {
                withTimeout(RESPONSE_TIMEOUT_MS) { connection.receiveFrame() }
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Failed to receive PAIR_RESPONSE frame")
                stateMachine.onCancelled("Failed to receive response")
                return PairingResult.Failure(ProtocolErrorCode.MALFORMED_PAYLOAD, "Connection dropped while awaiting response", e)
            }

            if (responseFrame.header.messageType != ProtocolConstants.MSG_PAIR_RESPONSE) {
                SafeLogger.w(TAG, "Unexpected message type received: 0x%02X".format(responseFrame.header.messageType))
                stateMachine.onCancelled("Unexpected message type")
                return PairingResult.Failure(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE, "Expected PAIR_RESPONSE, got 0x%02X".format(responseFrame.header.messageType))
            }

            if (responseFrame.header.requestId != requestId) {
                SafeLogger.w(TAG, "Request ID mismatch on PAIR_RESPONSE")
                stateMachine.onCancelled("Request ID mismatch")
                return PairingResult.Failure(ProtocolErrorCode.DEVICE_MISMATCH, "Request ID mismatch on pair response")
            }

            val pairResponse = try {
                ProtocolMessage.decode(responseFrame) as PairResponse
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Failed to decode PAIR_RESPONSE payload")
                stateMachine.onCancelled("Malformed PAIR_RESPONSE")
                return PairingResult.Failure(ProtocolErrorCode.MALFORMED_PAYLOAD, "Malformed PAIR_RESPONSE payload", e)
            }

            if (!pairResponse.isSuccess) {
                SafeLogger.w(TAG, "Host rejected pairing with status ${pairResponse.statusCode}")
                stateMachine.onCancelled("Host rejected pairing")
                return PairingResult.Failure(ProtocolErrorCode.CRYPTO_FAILURE, "Host rejected pairing with status ${pairResponse.statusCode}")
            }

            if (!Sec1P256.isValidSec1P256(pairResponse.serverPublicKey)) {
                SafeLogger.w(TAG, "Server public key is not a valid uncompressed SEC1 P-256 point")
                stateMachine.onCancelled("Invalid server public key")
                return PairingResult.Failure(ProtocolErrorCode.MALFORMED_PAYLOAD, "Server public key is not a valid uncompressed SEC1 P-256 point")
            }

            val expectedTag = PairingConfirmation.computeConfirmationTag(
                kPair = kPair,
                pcId = uri.pcId,
                deviceId = deviceId,
                clientPublicKey = clientPublicKey,
                serverPublicKey = pairResponse.serverPublicKey
            )

            if (!CryptoEngine.constantTimeEquals(expectedTag, pairResponse.confirmationTag)) {
                SafeLogger.w(TAG, "Pairing confirmation tag verification failed! Potential tampering detected.")
                stateMachine.onCancelled("Confirmation tag mismatch")
                return PairingResult.Failure(ProtocolErrorCode.CRYPTO_FAILURE, "Pairing confirmation tag verification failed")
            }

            val stateResult = stateMachine.onPairResponseReceived(
                requestId = requestId,
                response = pairResponse,
                kPair = kPair,
                expectedConfirmationTag = expectedTag
            )
            if (stateResult is ProtocolResult.Failure)
                return PairingResult.Failure(stateResult.errorCode, stateResult.message, stateResult.cause)
            val pairedRecord = (stateResult as ProtocolResult.Success).value
            try {
                pairedDeviceStore.savePairedPc(pairedRecord)
            } catch (e: Exception) {
                stateMachine.removePairedPc(pairedRecord.pcId)
                SafeLogger.e(TAG, "Failed to persist paired PC", e)
                return PairingResult.Failure(ProtocolErrorCode.CRYPTO_FAILURE, "Failed to persist paired PC", e)
            }

            SafeLogger.i(TAG, "Pairing verification and persistence successful for PC: ${uri.pcName}")
            try { onPairedConnection(pairedRecord,connection) } catch (_:Exception) { /* Optional display metadata. */ }
            return PairingResult.Success(pairedRecord)

        } finally {
            try {
                connection?.close()
            } catch (_: Exception) {}
            CryptoEngine.zeroize(kPair)
            uri.pairingToken.zeroize()
            SafeLogger.i(TAG, "Pairing socket closed and transient secrets zeroized")
        }
    }

    companion object {
        private const val TAG = "PairingCoordinator"
        const val DEFAULT_KEY_ALIAS = "wslp_companion_auth_key"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RESPONSE_TIMEOUT_MS = 15_000L
    }
}
