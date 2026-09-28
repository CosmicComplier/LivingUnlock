package com.windowslockpin.companion.core.crypto

import com.windowslockpin.companion.core.model.DeviceId
import com.windowslockpin.companion.core.model.PairingToken
import com.windowslockpin.companion.core.model.PcId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

object PairingConfirmation {

    private const val HKDF_INFO = "WSLP-V1-PAIRING-KEY"
    private const val TRANSCRIPT_PREFIX = "WSLP-V1-PAIR-CONFIRM\u0000"
    const val K_PAIR_LENGTH = 32
    const val CONFIRMATION_TAG_LENGTH = 32

    fun derivePairingSalt(pcId: PcId, deviceId: DeviceId): ByteArray {
        val pcIdBytes = pcId.value.toByteArray(StandardCharsets.UTF_8)
        val devIdBytes = deviceId.value.toByteArray(StandardCharsets.UTF_8)
        require(pcIdBytes.size <= 0xFFFF) { "pcId UTF-8 byte length exceeds uint16 max" }
        require(devIdBytes.size <= 0xFFFF) { "deviceId UTF-8 byte length exceeds uint16 max" }

        val baos = ByteArrayOutputStream(4 + pcIdBytes.size + devIdBytes.size)
        val dos = DataOutputStream(baos)
        dos.writeShort(pcIdBytes.size)
        dos.write(pcIdBytes)
        dos.writeShort(devIdBytes.size)
        dos.write(devIdBytes)
        dos.flush()

        return CryptoEngine.sha256(baos.toByteArray())
    }

    fun deriveKPair(pairingSalt: ByteArray, pairingToken: PairingToken): ByteArray {
        return CryptoEngine.hkdf(
            salt = pairingSalt,
            ikm = pairingToken.bytes,
            info = HKDF_INFO.toByteArray(StandardCharsets.UTF_8),
            outLen = K_PAIR_LENGTH
        )
    }

    fun buildConfirmationTranscript(
        pcId: PcId,
        deviceId: DeviceId,
        clientPublicKey: ByteArray,
        serverPublicKey: ByteArray
    ): ByteArray {
        val pcIdBytes = pcId.value.toByteArray(StandardCharsets.UTF_8)
        val devIdBytes = deviceId.value.toByteArray(StandardCharsets.UTF_8)
        require(pcIdBytes.size <= 0xFFFF) { "pcId UTF-8 byte length exceeds uint16 max" }
        require(devIdBytes.size <= 0xFFFF) { "deviceId UTF-8 byte length exceeds uint16 max" }
        require(clientPublicKey.size <= 0xFFFF) { "clientPublicKey byte length exceeds uint16 max" }
        require(serverPublicKey.size <= 0xFFFF) { "serverPublicKey byte length exceeds uint16 max" }

        val prefixBytes = TRANSCRIPT_PREFIX.toByteArray(StandardCharsets.US_ASCII)

        val baos = ByteArrayOutputStream(
            prefixBytes.size + 2 + pcIdBytes.size + 2 + devIdBytes.size + 2 + clientPublicKey.size + 2 + serverPublicKey.size
        )
        val dos = DataOutputStream(baos)
        dos.write(prefixBytes)
        dos.writeShort(pcIdBytes.size)
        dos.write(pcIdBytes)
        dos.writeShort(devIdBytes.size)
        dos.write(devIdBytes)
        dos.writeShort(clientPublicKey.size)
        dos.write(clientPublicKey)
        dos.writeShort(serverPublicKey.size)
        dos.write(serverPublicKey)
        dos.flush()

        return baos.toByteArray()
    }

    fun computeConfirmationTag(
        kPair: ByteArray,
        pcId: PcId,
        deviceId: DeviceId,
        clientPublicKey: ByteArray,
        serverPublicKey: ByteArray
    ): ByteArray {
        require(kPair.size == K_PAIR_LENGTH) { "K_pair must be 32 bytes, got ${kPair.size}" }
        val transcript = buildConfirmationTranscript(pcId, deviceId, clientPublicKey, serverPublicKey)
        return CryptoEngine.hmacSha256(kPair, transcript)
    }

    fun verifyConfirmationTag(
        kPair: ByteArray,
        pcId: PcId,
        deviceId: DeviceId,
        clientPublicKey: ByteArray,
        serverPublicKey: ByteArray,
        receivedTag: ByteArray
    ): Boolean {
        if (receivedTag.size != CONFIRMATION_TAG_LENGTH) {
            return false
        }
        val expectedTag = computeConfirmationTag(kPair, pcId, deviceId, clientPublicKey, serverPublicKey)
        return CryptoEngine.constantTimeEquals(expectedTag, receivedTag)
    }
}
