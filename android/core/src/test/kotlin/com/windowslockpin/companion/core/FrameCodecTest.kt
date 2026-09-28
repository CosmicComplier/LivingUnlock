package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class FrameCodecTest {

    @Test
    fun testValidFrameEncodeDecode() {
        val payload = "Hello, WindowsLockPin!".toByteArray()
        val reqId = RequestId(0x1122334455667788L)
        val frame = Frame.create(
            messageType = ProtocolConstants.MSG_PING,
            requestId = reqId,
            payload = payload
        )

        val encoded = frame.toByteArray()
        assertEquals(ProtocolConstants.HEADER_SIZE + payload.size, encoded.size)

        val decoded = Frame.decode(encoded)
        assertEquals(ProtocolConstants.PROTOCOL_VERSION, decoded.header.version)
        assertEquals(ProtocolConstants.MSG_PING, decoded.header.messageType)
        assertEquals(reqId.value, decoded.header.requestId.value)
        assertEquals(payload.size, decoded.header.payloadLength)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun testStreamReading() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val reqId = RequestId(42L)
        val frame = Frame.create(ProtocolConstants.MSG_PONG, reqId, payload)
        val encoded = frame.toByteArray()

        val inputStream = ByteArrayInputStream(encoded)
        val readFrame = Frame.readFromStream(inputStream)
        assertEquals(frame, readFrame)
    }

    @Test
    fun testRejectBadMagicBytes() {
        val payload = byteArrayOf(1, 2)
        val frame = Frame.create(ProtocolConstants.MSG_PING, RequestId(1L), payload)
        val bytes = frame.toByteArray()
        bytes[0] = 0x00.toByte() // Corrupt magic byte 0

        assertThrows(BadMagicException::class.java) {
            Frame.decode(bytes)
        }
    }

    @Test
    fun testRejectBadVersion() {
        val payload = byteArrayOf(1, 2)
        val frame = Frame.create(ProtocolConstants.MSG_PING, RequestId(1L), payload)
        val bytes = frame.toByteArray()
        bytes[2] = 0x02.toByte() // Version 2

        assertThrows(BadVersionException::class.java) {
            Frame.decode(bytes)
        }
    }

    @Test
    fun testRejectNonZeroReservedFlags() {
        val frame = Frame.create(ProtocolConstants.MSG_PING, RequestId(1L), byteArrayOf())
        val bytes = frame.toByteArray()
        bytes[5] = 0x01
        assertThrows(BadReservedFlagsException::class.java) { Frame.decode(bytes) }
    }

    @Test
    fun testCrossLanguageGoldenFrame() {
        val frame = Frame.create(
            ProtocolConstants.MSG_UNLOCK_CHALLENGE,
            RequestId(0x0102030405060708L),
            byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        )
        val expected = byteArrayOf(
            0x57, 0x4C, 0x01, 0x03, 0x00, 0x00, 0x01, 0x02,
            0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x00, 0x04,
            0x00, 0x01, 0x02, 0xFF.toByte()
        )
        assertArrayEquals(expected, frame.toByteArray())
    }

    @Test
    fun testRejectFrameOversize() {
        val hugePayload = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE + 1)
        assertThrows(FrameOversizeException::class.java) {
            Frame.create(ProtocolConstants.MSG_PING, RequestId(1L), hugePayload)
        }
    }

    @Test
    fun testRejectTruncatedFrame() {
        val frame = Frame.create(ProtocolConstants.MSG_PING, RequestId(1L), byteArrayOf(1, 2, 3))
        val bytes = frame.toByteArray()
        val truncated = bytes.copyOf(bytes.size - 1)

        assertThrows(MalformedFrameException::class.java) {
            Frame.decode(truncated)
        }
    }
}
