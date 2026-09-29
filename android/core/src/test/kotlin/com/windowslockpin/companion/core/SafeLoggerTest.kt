package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.SafeLogger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SafeLoggerTest {

    private val loggedMessages = mutableListOf<String>()
    private val sink = SafeLogger.LogSink { _, _, message ->
        loggedMessages.add(message)
    }

    @Before
    fun setUp() {
        loggedMessages.clear()
        SafeLogger.addSink(sink)
    }

    @After
    fun tearDown() {
        SafeLogger.removeSink(sink)
        loggedMessages.clear()
    }

    @Test
    fun testSanitizePairingToken() {
        val secretToken = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY"
        SafeLogger.i("TestTag", "Processing request with pair_token=$secretToken and other details")

        assertEquals(1, loggedMessages.size)
        val msg = loggedMessages[0]
        assertFalse("Secret token must never appear in log", msg.contains(secretToken))
        assertTrue("Redaction tag must be present", msg.contains("pair_token=[REDACTED]"))
    }

    @Test
    fun testSanitizeRawQrPayload() {
        val rawQr = "wslp://pair?v=1&pc_id=0123456789abcdef0123456789abcdef&pc_name=PC&bt_mac=AA:BB:CC:DD:EE:FF&pair_token=secret123&exp=1800000000"
        SafeLogger.w("TestTag", "Scanned raw QR: $rawQr from camera")

        assertEquals(1, loggedMessages.size)
        val msg = loggedMessages[0]
        assertFalse("Raw QR parameters must not appear in log", msg.contains("secret123"))
        assertFalse("Full PC ID must not appear in raw format", msg.contains("0123456789abcdef0123456789abcdef"))
        assertTrue("QR payload must be redacted", msg.contains("wslp://pair?[REDACTED_QR_PAYLOAD]"))
    }

    @Test
    fun testMaskFullHexIdentifiers() {
        val fullPcId = "abcdef0123456789abcdef0123456789"
        SafeLogger.d("TestTag", "Connected to host ID: $fullPcId successfully")

        assertEquals(1, loggedMessages.size)
        val msg = loggedMessages[0]
        assertFalse("Full 32-char ID must not appear", msg.contains(fullPcId))
        assertTrue("Masked format must appear", msg.contains("abcd...6789"))
    }

    @Test
    fun testThrowableMessageIsNeverLogged() {
        val secret = "pair_token=NeverLogThisValue123456"
        SafeLogger.e("TestTag", "Operation failed", IllegalStateException(secret))
        assertEquals(1, loggedMessages.size)
        assertFalse(loggedMessages[0].contains("NeverLogThisValue"))
        assertTrue(loggedMessages[0].contains("IllegalStateException"))
    }
}
