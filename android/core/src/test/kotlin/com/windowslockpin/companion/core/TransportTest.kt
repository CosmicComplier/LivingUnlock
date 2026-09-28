package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.Frame
import com.windowslockpin.companion.core.model.ProtocolConstants
import com.windowslockpin.companion.core.model.RequestId
import com.windowslockpin.companion.core.transport.FakeTransportConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TransportTest {

    @Test
    fun testDuplexFrameTransfer() {
        runBlocking {
            val (phoneConn, pcConn) = FakeTransportConnection.createPair()

            val reqId = RequestId(777L)
            val testPayload = "Hello RFCOMM".toByteArray()
            val outFrame = Frame.create(ProtocolConstants.MSG_PING, reqId, testPayload)

            phoneConn.sendFrame(outFrame)
            val inFrame = pcConn.receiveFrame()

            assertEquals(outFrame, inFrame)

            // PC sends back PONG
            val replyFrame = Frame.create(ProtocolConstants.MSG_PONG, reqId, ByteArray(0))
            pcConn.sendFrame(replyFrame)
            val phoneInFrame = phoneConn.receiveFrame()

            assertEquals(replyFrame, phoneInFrame)

            phoneConn.close()
            pcConn.close()
            assertFalse(phoneConn.isConnected)
            assertFalse(pcConn.isConnected)
        }
    }

    @Test
    fun testClosedConnectionThrowsOnSend() {
        runBlocking {
            val (phoneConn, _) = FakeTransportConnection.createPair()
            phoneConn.close()

            val frame = Frame.create(ProtocolConstants.MSG_PING, RequestId(1L), ByteArray(0))
            assertThrows(IOException::class.java) {
                runBlocking {
                    phoneConn.sendFrame(frame)
                }
            }
        }
    }
}
