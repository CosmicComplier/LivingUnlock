package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class CanonicalTranscriptTest {

    private val pcId = PcId.fromString("0123456789abcdef0123456789abcdef")
    private val devId = DeviceId("android-device-test-1234")
    private val reqId = RequestId(0x123456789ABCDEF0L)
    private val nonce = ChallengeNonce(ByteArray(32) { (it + 1).toByte() })
    private val timestampMs = 1700000000000L
    private val ttlMs = 30000L

    @Test
    fun testTranscriptPrefixAndStructure() {
        val transcript = CanonicalTranscript.build(
            pcId = pcId,
            deviceId = devId,
            requestId = reqId,
            challengeNonce = nonce,
            timestampMs = timestampMs,
            ttlMs = ttlMs
        )

        // 1. Prefix: 26 bytes: "WSLP-V1-UNLOCK-TRANSCRIPT\0"
        val expectedPrefix = ProtocolConstants.CANONICAL_TRANSCRIPT_PREFIX.toByteArray(StandardCharsets.US_ASCII)
        assertEquals(26, expectedPrefix.size)

        val actualPrefix = transcript.copyOfRange(0, 26)
        assertArrayEquals(expectedPrefix, actualPrefix)

        // 2. PC ID: length (1 byte) + bytes
        val pcIdLen = transcript[26].toInt() and 0xFF
        assertEquals(pcId.value.length, pcIdLen)
        val actualPcId = String(transcript.copyOfRange(27, 27 + pcIdLen), StandardCharsets.UTF_8)
        assertEquals(pcId.value, actualPcId)

        // Total expected size calculation
        val expectedTotal = 26 + 1 + pcId.value.length + 1 + devId.value.length + 8 + 1 + nonce.bytes.size + 8 + 4
        assertEquals(expectedTotal, transcript.size)
    }

    @Test
    fun testTranscriptStability() {
        val t1 = CanonicalTranscript.build(pcId, devId, reqId, nonce, timestampMs, ttlMs)
        val t2 = CanonicalTranscript.build(pcId, devId, reqId, nonce, timestampMs, ttlMs)
        assertArrayEquals("Transcript must be completely deterministic", t1, t2)
    }

    @Test
    fun testTranscriptSensitivity() {
        val baseline = CanonicalTranscript.build(pcId, devId, reqId, nonce, timestampMs, ttlMs)

        // Different request ID
        val altReq = CanonicalTranscript.build(pcId, devId, RequestId(reqId.value + 1), nonce, timestampMs, ttlMs)
        assertFalse(baseline.contentEquals(altReq))

        // Different timestamp
        val altTs = CanonicalTranscript.build(pcId, devId, reqId, nonce, timestampMs + 1000, ttlMs)
        assertFalse(baseline.contentEquals(altTs))

        // Different nonce
        val altNonce = ChallengeNonce(ByteArray(32) { (it + 2).toByte() })
        val altNonceT = CanonicalTranscript.build(pcId, devId, reqId, altNonce, timestampMs, ttlMs)
        assertFalse(baseline.contentEquals(altNonceT))
    }
}
