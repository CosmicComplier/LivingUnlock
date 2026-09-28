package com.windowslockpin.companion.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

object CanonicalTranscript {
    private val PREFIX_BYTES = ProtocolConstants.CANONICAL_TRANSCRIPT_PREFIX.toByteArray(StandardCharsets.US_ASCII)

    fun build(
        pcId: PcId,
        deviceId: DeviceId,
        requestId: RequestId,
        challengeNonce: ChallengeNonce,
        timestampMs: Long,
        ttlMs: Long
    ): ByteArray {
        val pcIdBytes = pcId.value.toByteArray(StandardCharsets.UTF_8)
        require(pcIdBytes.size in 1..255) { "pcId UTF-8 byte length must fit in uint8" }

        val devIdBytes = deviceId.value.toByteArray(StandardCharsets.UTF_8)
        require(devIdBytes.size in 1..255) { "deviceId UTF-8 byte length must fit in uint8" }

        require(challengeNonce.bytes.size in 1..255) { "challengeNonce byte length must fit in uint8" }

        val baos = ByteArrayOutputStream(
            PREFIX_BYTES.size + 1 + pcIdBytes.size + 1 + devIdBytes.size + 8 + 1 + challengeNonce.bytes.size + 8 + 4
        )
        val dos = DataOutputStream(baos)

        // 1. Prefix with null terminator: "WSLP-V1-UNLOCK-TRANSCRIPT\0"
        dos.write(PREFIX_BYTES)

        // 2. Length of PC ID (uint8) + PC ID bytes
        dos.writeByte(pcIdBytes.size)
        dos.write(pcIdBytes)

        // 3. Length of Device ID (uint8) + Device ID bytes
        dos.writeByte(devIdBytes.size)
        dos.write(devIdBytes)

        // 4. Request ID (uint64, Big-Endian)
        dos.writeLong(requestId.value)

        // 5. Length of Challenge Nonce (uint8) + Challenge Nonce bytes
        dos.writeByte(challengeNonce.bytes.size)
        dos.write(challengeNonce.bytes)

        // 6. Timestamp (uint64, Big-Endian, UTC milliseconds)
        dos.writeLong(timestampMs)

        // 7. TTL (uint32, Big-Endian, milliseconds)
        dos.writeInt(ttlMs.toInt())

        dos.flush()
        return baos.toByteArray()
    }
}
