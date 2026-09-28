package com.windowslockpin.companion.core.transport

import com.windowslockpin.companion.core.model.BluetoothMacAddress
import com.windowslockpin.companion.core.model.Frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException

class FakeTransportConnection(
    private val incomingChannel: Channel<Frame>,
    private val outgoingChannel: Channel<Frame>
) : TransportConnection {
    @Volatile
    private var closed = false

    override val isConnected: Boolean
        get() = !closed

    override suspend fun sendFrame(frame: Frame) {
        if (!isConnected) throw IOException("Transport connection is closed")
        outgoingChannel.send(frame)
    }

    override suspend fun receiveFrame(): Frame {
        if (!isConnected) throw IOException("Transport connection is closed")
        return try {
            incomingChannel.receive()
        } catch (e: Exception) {
            throw IOException("Failed to receive frame from channel", e)
        }
    }

    suspend fun receiveFrameWithTimeout(timeoutMs: Long): Frame {
        return withTimeout(timeoutMs) {
            receiveFrame()
        }
    }

    override fun close() {
        closed = true
        incomingChannel.close()
        outgoingChannel.close()
    }

    companion object {
        fun createPair(): Pair<FakeTransportConnection, FakeTransportConnection> {
            val phoneToPc = Channel<Frame>(Channel.BUFFERED)
            val pcToPhone = Channel<Frame>(Channel.BUFFERED)

            val phoneConnection = FakeTransportConnection(
                incomingChannel = pcToPhone,
                outgoingChannel = phoneToPc
            )
            val pcConnection = FakeTransportConnection(
                incomingChannel = phoneToPc,
                outgoingChannel = pcToPhone
            )
            return Pair(phoneConnection, pcConnection)
        }
    }
}

class FakeTransportClient(
    private val connectionProvider: (BluetoothMacAddress) -> FakeTransportConnection
) : TransportClient {
    override suspend fun connect(macAddress: BluetoothMacAddress): TransportConnection {
        return connectionProvider(macAddress)
    }
}
