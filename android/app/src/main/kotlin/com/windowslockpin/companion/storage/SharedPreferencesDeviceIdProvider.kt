package com.windowslockpin.companion.storage

import android.content.Context
import com.windowslockpin.companion.core.model.DeviceId
import com.windowslockpin.companion.core.storage.DeviceIdProvider
import java.util.UUID

class SharedPreferencesDeviceIdProvider(context: Context) : DeviceIdProvider {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun getDeviceId(): DeviceId {
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (existing != null) return DeviceId(existing)
        val generated = DeviceId("android-${UUID.randomUUID().toString().replace("-", "")}")
        check(preferences.edit().putString(KEY_DEVICE_ID, generated.value).commit()) {
            "Failed to persist installation device ID"
        }
        return generated
    }

    companion object {
        private const val PREFERENCES = "wslp_installation_v1"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
