package com.windowslockpin.companion.core.transport

import com.windowslockpin.companion.core.model.BluetoothMacAddress
import com.windowslockpin.companion.core.model.Frame
import java.io.Closeable

interface TransportConnection : Closeable {
    val isConnected: Boolean
    suspend fun sendFrame(frame: Frame)
    suspend fun receiveFrame(): Frame
}

interface TransportClient {
    suspend fun connect(macAddress: BluetoothMacAddress): TransportConnection
}
