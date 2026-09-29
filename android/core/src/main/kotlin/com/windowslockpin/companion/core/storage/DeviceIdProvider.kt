package com.windowslockpin.companion.core.storage

import com.windowslockpin.companion.core.model.DeviceId

interface DeviceIdProvider {
    fun getDeviceId(): DeviceId
}

class FixedDeviceIdProvider(private val deviceId: DeviceId) : DeviceIdProvider {
    override fun getDeviceId(): DeviceId = deviceId
}
