package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.BluetoothMacAddress
import com.windowslockpin.companion.core.model.PcId
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.storage.EncryptedFilePairedDeviceStore
import com.windowslockpin.companion.core.storage.EncryptedRecordCodec
import com.windowslockpin.companion.core.storage.FakePairedDeviceStore
import com.windowslockpin.companion.core.storage.JvmRecordCipher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException
import java.security.SecureRandom

class StorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleKey = ByteArray(32) { it.toByte() }
    private val cipher = JvmRecordCipher(sampleKey)
    private val validServerPublicKey = hexToBytes("04ff8fd466638dd50665c4fe222678b2e9084e9a51820b460476e96d9fefd788103bd828fb4d9d892480c94814cbe8d901365bfc87d1241007daa66071f40a2dbc")

    private val sampleRecord = PairedPcRecord(
        pcId = PcId.fromString("0123456789abcdef0123456789abcdef"),
        pcName = "My Desktop",
        bluetoothMac = BluetoothMacAddress.parse("AA:BB:CC:DD:EE:FF"),
        serverPublicKey = validServerPublicKey,
        kPair = ByteArray(32) { (it + 42).toByte() },
        pairedTimestampMs = 1700000000000L
    )

    @Test
    fun testCodecRoundtrip() {
        val encrypted = EncryptedRecordCodec.serialize(sampleRecord, cipher)
        assertTrue(encrypted.size > 12 + 16) // IV + tag + payload

        val decrypted = EncryptedRecordCodec.deserialize(encrypted, cipher, sampleRecord.pcId)
        assertEquals(sampleRecord.pcId, decrypted.pcId)
        assertEquals(sampleRecord.pcName, decrypted.pcName)
        assertEquals(sampleRecord.bluetoothMac, decrypted.bluetoothMac)
        assertArrayEquals(sampleRecord.serverPublicKey, decrypted.serverPublicKey)
        assertArrayEquals(sampleRecord.kPair, decrypted.kPair)
        assertEquals(sampleRecord.pairedTimestampMs, decrypted.pairedTimestampMs)
        assertEquals(sampleRecord, decrypted)
    }

    @Test
    fun testAadTamperingFails() {
        val encrypted = EncryptedRecordCodec.serialize(sampleRecord, cipher)
        val wrongPcId = PcId.fromString("fedcba9876543210fedcba9876543210")

        try {
            EncryptedRecordCodec.deserialize(encrypted, cipher, wrongPcId)
            fail("Expected decryption to fail due to AAD mismatch")
        } catch (_: GeneralSecurityException) {
            // Expected
        } catch (_: Exception) {
            // Expected
        }
    }

    @Test
    fun testCiphertextTamperingFails() {
        val encrypted = EncryptedRecordCodec.serialize(sampleRecord, cipher)
        val corrupted = encrypted.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()

        try {
            EncryptedRecordCodec.deserialize(corrupted, cipher, sampleRecord.pcId)
            fail("Expected decryption to fail due to corrupted ciphertext tag")
        } catch (_: GeneralSecurityException) {
            // Expected
        } catch (_: Exception) {
            // Expected
        }
    }

    @Test
    fun testEncryptedFileStoreCrud() {
        val storageDir = tempFolder.newFolder("paired_devices")
        val store = EncryptedFilePairedDeviceStore(storageDir, cipher)

        assertTrue(store.getPairedPcs().isEmpty())
        assertNull(store.getPairedPc(sampleRecord.pcId))

        // Save
        store.savePairedPc(sampleRecord)

        val retrieved = store.getPairedPc(sampleRecord.pcId)
        assertNotNull(retrieved)
        assertEquals(sampleRecord, retrieved)

        val all = store.getPairedPcs()
        assertEquals(1, all.size)
        assertEquals(sampleRecord, all[0])

        // Add a second record
        val secondRecord = PairedPcRecord(
            pcId = PcId.fromString("11112222333344445555666677778888"),
            pcName = "Office Laptop",
            bluetoothMac = BluetoothMacAddress.parse("11:22:33:44:55:66"),
            serverPublicKey = validServerPublicKey,
            kPair = ByteArray(32) { 0x01 },
            pairedTimestampMs = 1700000010000L
        )
        store.savePairedPc(secondRecord)
        assertEquals(2, store.getPairedPcs().size)

        // Remove first record
        val removed = store.removePairedPc(sampleRecord.pcId)
        assertTrue(removed)
        assertNull(store.getPairedPc(sampleRecord.pcId))
        assertEquals(1, store.getPairedPcs().size)
        assertEquals(secondRecord, store.getPairedPcs()[0])

        // Clear all
        store.clear()
        assertTrue(store.getPairedPcs().isEmpty())
    }

    @Test
    fun testCorruptedFileIgnoredInList() {
        val storageDir = tempFolder.newFolder("corrupt_test")
        val store = EncryptedFilePairedDeviceStore(storageDir, cipher)

        store.savePairedPc(sampleRecord)

        // Create a corrupted file in the directory
        val corruptFile = java.io.File(storageDir, "badrecord12345678badrecord12345678.enc")
        corruptFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

        val list = store.getPairedPcs()
        assertEquals(1, list.size)
        assertEquals(sampleRecord, list[0])
    }

    @Test
    fun testFakePairedDeviceStore() {
        val fakeStore = FakePairedDeviceStore()
        assertTrue(fakeStore.getPairedPcs().isEmpty())
        fakeStore.savePairedPc(sampleRecord)
        assertEquals(1, fakeStore.getPairedPcs().size)
        assertEquals(sampleRecord, fakeStore.getPairedPc(sampleRecord.pcId))
        assertTrue(fakeStore.removePairedPc(sampleRecord.pcId))
        assertFalse(fakeStore.removePairedPc(sampleRecord.pcId))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
