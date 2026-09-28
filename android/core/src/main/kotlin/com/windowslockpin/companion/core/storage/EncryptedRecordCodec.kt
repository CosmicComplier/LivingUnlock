package com.windowslockpin.companion.core.storage

import com.windowslockpin.companion.core.crypto.CryptoEngine
import com.windowslockpin.companion.core.model.BluetoothMacAddress
import com.windowslockpin.companion.core.model.PcId
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import com.windowslockpin.companion.core.crypto.Sec1P256

object EncryptedRecordCodec {

    private const val RECORD_MAGIC_0: Byte = 0x57.toByte() // 'W'
    private const val RECORD_MAGIC_1: Byte = 0x50.toByte() // 'P'
    private const val RECORD_VERSION: Byte = 1
    private const val AAD_PREFIX = "WSLP-V1-RECORD-AAD:"

    fun computeAad(pcId: PcId): ByteArray {
        return (AAD_PREFIX + pcId.value).toByteArray(StandardCharsets.UTF_8)
    }

    fun serialize(record: PairedPcRecord, cipher: RecordCipher): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeByte(RECORD_MAGIC_0.toInt())
        dos.writeByte(RECORD_MAGIC_1.toInt())
        dos.writeByte(RECORD_VERSION.toInt())

        val pcIdBytes = record.pcId.value.toByteArray(StandardCharsets.UTF_8)
        require(pcIdBytes.size in 16..64)
        dos.writeShort(pcIdBytes.size)
        dos.write(pcIdBytes)

        val pcNameBytes = record.pcName.toByteArray(StandardCharsets.UTF_8)
        require(pcNameBytes.size in 1..64)
        dos.writeShort(pcNameBytes.size)
        dos.write(pcNameBytes)

        val macBytes = record.bluetoothMac.value.toByteArray(StandardCharsets.UTF_8)
        require(macBytes.size == 17)
        dos.writeShort(macBytes.size)
        dos.write(macBytes)

        require(Sec1P256.isValidSec1P256(record.serverPublicKey))
        dos.writeShort(record.serverPublicKey.size)
        dos.write(record.serverPublicKey)

        require(record.kPair.size == 32)
        dos.writeShort(record.kPair.size)
        dos.write(record.kPair)

        dos.writeLong(record.pairedTimestampMs)
        dos.flush()

        val plaintext = baos.toByteArray()
        require(plaintext.size <= MAX_PLAINTEXT_SIZE)
        val aad = computeAad(record.pcId)
        return try {
            cipher.encrypt(plaintext, aad)
        } finally {
            CryptoEngine.zeroize(plaintext)
        }
    }

    fun deserialize(encryptedBytes: ByteArray, cipher: RecordCipher, expectedPcId: PcId): PairedPcRecord {
        val aad = computeAad(expectedPcId)
        val plaintext = cipher.decrypt(encryptedBytes, aad)
        return try {
            require(plaintext.size <= MAX_PLAINTEXT_SIZE) { "Decrypted pairing record is oversized" }
            val dis = DataInputStream(ByteArrayInputStream(plaintext))

            val m0 = dis.readByte()
            val m1 = dis.readByte()
            if (m0 != RECORD_MAGIC_0 || m1 != RECORD_MAGIC_1) {
                throw IllegalArgumentException("Invalid record magic bytes")
            }

            val version = dis.readByte()
            if (version != RECORD_VERSION) {
                throw IllegalArgumentException("Unsupported record version: $version")
            }

            val pcIdLen = dis.readUnsignedShort()
            require(pcIdLen in 16..64) { "Invalid PC ID length" }
            val pcIdBytes = ByteArray(pcIdLen)
            dis.readFully(pcIdBytes)
            val pcId = PcId.fromString(decodeUtf8Strict(pcIdBytes))
            if (pcId != expectedPcId) {
                throw IllegalArgumentException("Decrypted record pcId does not match expected pcId")
            }

            val nameLen = dis.readUnsignedShort()
            require(nameLen in 1..64) { "Invalid PC name length" }
            val nameBytes = ByteArray(nameLen)
            dis.readFully(nameBytes)
            val pcName = decodeUtf8Strict(nameBytes)

            val macLen = dis.readUnsignedShort()
            require(macLen == 17) { "Invalid Bluetooth address length" }
            val macBytes = ByteArray(macLen)
            dis.readFully(macBytes)
            val mac = BluetoothMacAddress.parse(decodeUtf8Strict(macBytes))

            val keyLen = dis.readUnsignedShort()
            require(keyLen == 65) { "Invalid server public key length" }
            val serverPublicKey = ByteArray(keyLen)
            dis.readFully(serverPublicKey)
            require(Sec1P256.isValidSec1P256(serverPublicKey)) { "Invalid server public key" }

            val kPairLen = dis.readUnsignedShort()
            require(kPairLen == 32) { "Invalid pairing-key length" }
            val kPair = ByteArray(kPairLen)
            dis.readFully(kPair)

            val timestamp = dis.readLong()
            require(dis.available() == 0) { "Trailing bytes in pairing record" }

            PairedPcRecord(
                pcId = pcId,
                pcName = pcName,
                bluetoothMac = mac,
                serverPublicKey = serverPublicKey,
                kPair = kPair,
                pairedTimestampMs = timestamp
            )
        } finally {
            CryptoEngine.zeroize(plaintext)
        }
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()

    private const val MAX_PLAINTEXT_SIZE = 1024
}
