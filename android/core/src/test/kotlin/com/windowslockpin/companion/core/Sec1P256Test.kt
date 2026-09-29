package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.crypto.PairingConfirmation
import com.windowslockpin.companion.core.crypto.Sec1P256
import com.windowslockpin.companion.core.model.DeviceId
import com.windowslockpin.companion.core.model.PairingToken
import com.windowslockpin.companion.core.model.PcId
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class Sec1P256Test {

    // NIST P-256 standard generator G
    private val gxHex = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
    private val gyHex = "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
    private val gSec1Hex = "04$gxHex$gyHex"

    @Test
    fun testGeneratorGIsOnCurve() {
        val x = BigInteger(gxHex, 16)
        val y = BigInteger(gyHex, 16)
        assertTrue(Sec1P256.isOnCurve(x, y))
    }

    @Test
    fun testDecodeAndEncodeGenerator() {
        val gBytes = hexToBytes(gSec1Hex)
        val pubKey = Sec1P256.decodePublicKey(gBytes)
        assertNotNull(pubKey)
        val reencoded = Sec1P256.encodePublicKey(pubKey)
        assertArrayEquals(gBytes, reencoded)
    }

    @Test
    fun testGeneratedKeyPairEncodeDecodeRoundtrip() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val pub = kp.public as ECPublicKey

        val encoded = Sec1P256.encodePublicKey(pub)
        assertEquals(65, encoded.size)
        assertEquals(0x04.toByte(), encoded[0])

        val decoded = Sec1P256.decodePublicKey(encoded)
        assertEquals(pub.w.affineX, decoded.w.affineX)
        assertEquals(pub.w.affineY, decoded.w.affineY)
    }

    @Test
    fun testRejectInvalidLength() {
        val shortKey = ByteArray(64) { 0x04 }
        assertThrows(IllegalArgumentException::class.java) {
            Sec1P256.decodePublicKey(shortKey)
        }
    }

    @Test
    fun testRejectInvalidPrefix() {
        val badPrefix = hexToBytes(gSec1Hex)
        badPrefix[0] = 0x02 // compressed prefix rejected in strict uncompressed mode
        assertThrows(IllegalArgumentException::class.java) {
            Sec1P256.decodePublicKey(badPrefix)
        }
    }

    @Test
    fun testRejectPointNotOnCurve() {
        val badPoint = hexToBytes(gSec1Hex)
        badPoint[1] = (badPoint[1].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            Sec1P256.decodePublicKey(badPoint)
        }
    }

    @Test
    fun testPairingConfirmationDerivations() {
        val pcId = PcId.fromString("0123456789abcdef0123456789abcdef")
        val devId = DeviceId("test-device-id-1234")
        val token = PairingToken(ByteArray(32) { 0x42 })
        val clientKey = hexToBytes(gSec1Hex)
        val serverKey = hexToBytes(gSec1Hex)

        val salt = PairingConfirmation.derivePairingSalt(pcId, devId)
        assertEquals(32, salt.size)

        val kPair = PairingConfirmation.deriveKPair(salt, token)
        assertEquals(32, kPair.size)

        val transcript = PairingConfirmation.buildConfirmationTranscript(pcId, devId, clientKey, serverKey)
        assertTrue(transcript.isNotEmpty())

        val tag = PairingConfirmation.computeConfirmationTag(kPair, pcId, devId, clientKey, serverKey)
        assertEquals(32, tag.size)

        val valid = PairingConfirmation.verifyConfirmationTag(kPair, pcId, devId, clientKey, serverKey, tag)
        assertTrue(valid)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
