package com.windowslockpin.companion.storage
import android.content.Context
import com.windowslockpin.companion.core.crypto.DeviceInfoEnvelope
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import org.json.JSONObject

class DeviceDetailsStore(context: Context) {
    private val prefs=context.getSharedPreferences("livingunlock_device_details",Context.MODE_PRIVATE)
    fun nickname(record: PairedPcRecord)=prefs.getString("nick.${record.pcId.value}","").orEmpty()
    fun displayName(record: PairedPcRecord)=nickname(record).let { if(it.isBlank()) record.pcName else "$it*" }
    fun saveNickname(record: PairedPcRecord, value:String) {
        val name=value.trim();require(name.length<=40 && name.none { it.isISOControl() })
        prefs.edit().putString("nick.${record.pcId.value}",name).apply()
    }
    fun details(record: PairedPcRecord):JSONObject?=runCatching {
        prefs.getString("info.${record.pcId.value}",null)?.let(::JSONObject)
    }.getOrNull()
    fun accept(record: PairedPcRecord,payload:ByteArray) {
        val text=DeviceInfoEnvelope.decrypt(payload,record.kPair)
        val info=JSONObject(text)
        require(info.getInt("v")==1 && info.getString("pcId")==record.pcId.value)
        val captured=info.getLong("capturedMs")
        require(captured>0 && captured<=System.currentTimeMillis()+60_000)
        if(captured < (details(record)?.optLong("capturedMs")?:0)) return
        prefs.edit().putString("info.${record.pcId.value}",text).apply()
    }
    fun remove(record:PairedPcRecord) {
        prefs.edit().remove("nick.${record.pcId.value}").remove("info.${record.pcId.value}").apply()
    }
}
