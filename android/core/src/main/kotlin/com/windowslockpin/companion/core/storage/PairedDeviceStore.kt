package com.windowslockpin.companion.core.storage

import com.windowslockpin.companion.core.model.PcId
import com.windowslockpin.companion.core.statemachine.PairedPcRecord

interface PairedDeviceStore {
    fun getPairedPcs(): List<PairedPcRecord>
    fun getPairedPc(pcId: PcId): PairedPcRecord?
    fun savePairedPc(record: PairedPcRecord)
    fun removePairedPc(pcId: PcId): Boolean
    fun clear()
}
