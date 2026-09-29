package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.crypto.PairingConfirmation
import com.windowslockpin.companion.core.crypto.Sec1P256
import com.windowslockpin.companion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class GoldenVectorTest {

    private val pcIdHex = "0123456789abcdef0123456789abcdef"
    private val deviceIdStr = "test-installation-device-id-001"
    private val pairTokenHex = "4242424242424242424242424242424242424242424242424242424242424242"
    private val clientPublicKeyHex = "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
    private val serverPublicKeyHex = "04ff8fd466638dd50665c4fe222678b2e9084e9a51820b460476e96d9fefd788103bd828fb4d9d892480c94814cbe8d901365bfc87d1241007daa66071f40a2dbc"

    private val expectedSaltHex = "b01c84e59e9003f1a55807e7f3b496e4b05b4567f7c9380d97e36ba20062f34b"
    private val expectedKPairHex = "abd3f5110775c9c8eb7fe34b83755fe3dcdf7d9b177e3bb9f0ccc278d3c992ff"
    private val expectedTranscriptHex = "57534c502d56312d504149522d434f4e4649524d0000203031323334353637383961626364656630313233343536373839616263646566001f746573742d696e7374616c6c6174696f6e2d6465766963652d69642d3030310041046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5004104ff8fd466638dd50665c4fe222678b2e9084e9a51820b460476e96d9fefd788103bd828fb4d9d892480c94814cbe8d901365bfc87d1241007daa66071f40a2dbc"
    private val expectedTagHex = "987910675225b4d2dea78c530350c32cbb53a99c8c06f9f71426c7103cea7c6b"

    @Test
    fun testGoldenVectorDerivations() {
        val pcId = PcId.fromString(pcIdHex)
        val devId = DeviceId(deviceIdStr)
        val token = PairingToken(hexToBytes(pairTokenHex))
        val clientKey = hexToBytes(clientPublicKeyHex)
        val serverKey = hexToBytes(serverPublicKeyHex)

        // Verify public keys are valid SEC1 points
        assertTrue("Client key must be valid SEC1 P-256", Sec1P256.isValidSec1P256(clientKey))
        assertTrue("Server key must be valid SEC1 P-256", Sec1P256.isValidSec1P256(serverKey))

        // 1. Pairing Salt
        val salt = PairingConfirmation.derivePairingSalt(pcId, devId)
        assertEquals("Pairing salt must match golden vector", expectedSaltHex, bytesToHex(salt))

        // 2. K_pair
        val kPair = PairingConfirmation.deriveKPair(salt, token)
        assertEquals("K_pair must match golden vector", expectedKPairHex, bytesToHex(kPair))

        // 3. Confirmation Transcript
        val transcript = PairingConfirmation.buildConfirmationTranscript(pcId, devId, clientKey, serverKey)
        assertEquals("Confirmation transcript must match golden vector", expectedTranscriptHex, bytesToHex(transcript))

        // 4. Confirmation Tag
        val tag = PairingConfirmation.computeConfirmationTag(kPair, pcId, devId, clientKey, serverKey)
        assertEquals("Confirmation tag must match golden vector", expectedTagHex, bytesToHex(tag))

        // 5. Verification
        assertTrue(
            "Confirmation tag verification must succeed",
            PairingConfirmation.verifyConfirmationTag(kPair, pcId, devId, clientKey, serverKey, hexToBytes(expectedTagHex))
        )
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
