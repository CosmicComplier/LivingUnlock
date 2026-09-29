package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PairingUriTest {

    private val validPcId = "0123456789abcdef0123456789abcdef"
    private val validName = "My-Desktop_PC 1"
    private val validMac = "AA:BB:CC:DD:EE:FF"
    // 32-byte Base64URL string (43 chars)
    private val validTokenB64 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY"
    private val validExp = 1800000000L

    private fun buildUriString(
        v: String = "1",
        pcId: String = validPcId,
        pcName: String = validName,
        btMac: String = validMac,
        token: String = validTokenB64,
        exp: String = validExp.toString(),
        extraParams: String = ""
    ): String {
        val base = "wslp://pair?v=$v&pc_id=$pcId&pc_name=$pcName&bt_mac=$btMac&pair_token=$token&exp=$exp"
        return if (extraParams.isNotEmpty()) "$base&$extraParams" else base
    }

    @Test
    fun testValidUriParsing() {
        val uriStr = buildUriString()
        val parsed = PairingUri.parse(uriStr, currentEpochSec = validExp - 10)

        assertEquals(1, parsed.version)
        assertEquals(validPcId.lowercase(), parsed.pcId.value)
        assertEquals(validName, parsed.pcName)
        assertEquals(validMac, parsed.bluetoothMac.value)
        assertEquals(validExp, parsed.expiryEpochSec)
        assertFalse(parsed.isExpired(validExp - 1))
        assertTrue(parsed.isExpired(validExp + 1))
    }

    @Test
    fun testRejectUnknownScheme() {
        val badScheme = "otpauth://pair?v=1&pc_id=$validPcId&pc_name=$validName&bt_mac=$validMac&pair_token=$validTokenB64&exp=$validExp"
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(badScheme)
        }
        assertTrue(ex.message!!.contains("Invalid URI scheme"))
    }

    @Test
    fun testRejectUnsupportedVersion() {
        val badVersion = buildUriString(v = "2")
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(badVersion)
        }
        assertTrue(ex.message!!.contains("Unsupported protocol version"))
    }

    @Test
    fun testRejectUnknownParameter() {
        val extraParam = buildUriString(extraParams = "unknown_flag=true")
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(extraParam)
        }
        assertTrue(ex.message!!.contains("Unknown query parameter"))
    }

    @Test
    fun testRejectDuplicateParameter() {
        val duplicateParam = buildUriString(extraParams = "v=1")
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(duplicateParam)
        }
        assertTrue(ex.message!!.contains("Duplicate query parameter"))
    }

    @Test
    fun testRejectMissingRequiredParameter() {
        val missingToken = "wslp://pair?v=1&pc_id=$validPcId&pc_name=$validName&bt_mac=$validMac&exp=$validExp"
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(missingToken)
        }
        assertTrue(ex.message!!.contains("Missing required query parameter 'pair_token'"))
    }

    @Test
    fun testRejectOversizeUri() {
        val longPadding = "a".repeat(600)
        val longUri = "wslp://pair?v=1&pc_id=$validPcId&pc_name=$longPadding&bt_mac=$validMac&pair_token=$validTokenB64&exp=$validExp"
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(longUri)
        }
        assertTrue(ex.message!!.contains("exceeds maximum length"))
    }

    @Test
    fun testRejectInvalidPcIdCharacters() {
        val badPcId = buildUriString(pcId = "not_valid_hex_characters_xyz!!")
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(badPcId)
        }
        assertTrue(ex.message!!.contains("Invalid pc_id"))
    }

    @Test
    fun testRejectInvalidMacAddress() {
        val badMac = buildUriString(btMac = "00-11-22-33-44-55")
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(badMac)
        }
        assertTrue(ex.message!!.contains("Invalid bt_mac"))
    }

    @Test
    fun testRejectInvalidBase64UrlToken() {
        // '+' is standard Base64 but disallowed in Base64URL unpadded
        val invalidToken = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0N+=="
        val badTokenUri = buildUriString(token = invalidToken)
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(badTokenUri)
        }
        assertTrue(ex.message!!.contains("Invalid pair_token"))
    }

    @Test
    fun testRejectExpiredUri() {
        val expiredUri = buildUriString(exp = "1000")
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(expiredUri, currentEpochSec = 2000)
        }
        assertTrue(ex.message!!.contains("expired"))
    }

    @Test
    fun testRejectExcessiveFutureExpiry() {
        val current = 1_700_000_000L
        val futureUri = buildUriString(exp = (current + ProtocolConstants.MAX_QR_EXPIRY_WINDOW_SEC + 1).toString())
        val ex = assertThrows(MalformedPairingUriException::class.java) {
            PairingUri.parse(futureUri, currentEpochSec = current)
        }
        assertTrue(ex.message!!.contains("maximum allowed window"))
    }

    @Test
    fun testToStringRedactsTokenAndFullPcId() {
        val uri = PairingUri.parse(buildUriString())
        val str = uri.toString()
        assertFalse(str.contains(validTokenB64))
        assertFalse(str.contains(validPcId))
        assertTrue(str.contains("[REDACTED]"))
        assertTrue(str.contains("0123...cdef"))
        assertFalse(str.contains(validMac))
    }
}
