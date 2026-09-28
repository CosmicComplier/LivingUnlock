package com.windowslockpin.companion.core.storage

import com.windowslockpin.companion.core.model.PcId
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import java.util.concurrent.ConcurrentHashMap

class FakePairedDeviceStore : PairedDeviceStore {
    private val records = ConcurrentHashMap<String, PairedPcRecord>()

    override fun getPairedPcs(): List<PairedPcRecord> = records.values.toList()

    override fun getPairedPc(pcId: PcId): PairedPcRecord? = records[pcId.value]

    override fun savePairedPc(record: PairedPcRecord) {
        records[record.pcId.value] = record
    }

    override fun removePairedPc(pcId: PcId): Boolean = records.remove(pcId.value) != null

    override fun clear() {
        records.clear()
    }
}
