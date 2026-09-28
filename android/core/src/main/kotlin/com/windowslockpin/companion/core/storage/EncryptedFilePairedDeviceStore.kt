package com.windowslockpin.companion.core.storage

import com.windowslockpin.companion.core.model.PcId
import com.windowslockpin.companion.core.model.SafeLogger
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class EncryptedFilePairedDeviceStore(
    private val storageDir: File,
    private val cipher: RecordCipher
) : PairedDeviceStore {

    init {
        if (!storageDir.exists()) {
            check(storageDir.mkdirs()) { "Failed to create paired-device storage directory" }
        }
        require(storageDir.isDirectory) { "Paired-device storage path is not a directory" }
    }

    private fun getFileForPc(pcId: PcId): File = File(storageDir, "${pcId.value}.enc")

    @Synchronized
    override fun getPairedPcs(): List<PairedPcRecord> {
        val files = storageDir.listFiles { file -> file.isFile && file.name.endsWith(".enc") } ?: return emptyList()
        val result = mutableListOf<PairedPcRecord>()
        for (file in files) {
            val pcIdStr = file.name.removeSuffix(".enc")
            try {
                val pcId = PcId.fromString(pcIdStr)
                if (file.length() <= 0 || file.length() > MAX_ENCRYPTED_FILE_SIZE) continue
                val bytes = file.readBytes()
                val record = EncryptedRecordCodec.deserialize(bytes, cipher, pcId)
                result.add(record)
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Failed to load encrypted record file: ${file.name}")
            }
        }
        return result
    }

    @Synchronized
    override fun getPairedPc(pcId: PcId): PairedPcRecord? {
        val file = getFileForPc(pcId)
        if (!file.exists()) return null
        if (file.length() <= 0 || file.length() > MAX_ENCRYPTED_FILE_SIZE) return null
        return try {
            val bytes = file.readBytes()
            EncryptedRecordCodec.deserialize(bytes, cipher, pcId)
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Failed to decrypt record for PC")
            null
        }
    }

    @Synchronized
    override fun savePairedPc(record: PairedPcRecord) {
        val targetFile = getFileForPc(record.pcId)
        val tempFile = File(storageDir, "${record.pcId.value}.tmp")
        if (tempFile.exists() && !tempFile.delete()) throw IllegalStateException("Failed to remove stale pairing temp file")
        val encrypted = EncryptedRecordCodec.serialize(record, cipher)

        FileOutputStream(tempFile).use { fos ->
            fos.write(encrypted)
            fos.fd.sync()
        }

        try {
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    override fun removePairedPc(pcId: PcId): Boolean {
        val file = getFileForPc(pcId)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    @Synchronized
    override fun clear() {
        val files = storageDir.listFiles { file -> file.isFile && file.name.endsWith(".enc") } ?: return
        for (file in files) {
            file.delete()
        }
    }

    companion object {
        private const val TAG = "EncryptedFileStore"
        private const val MAX_ENCRYPTED_FILE_SIZE = 4096L
    }
}
