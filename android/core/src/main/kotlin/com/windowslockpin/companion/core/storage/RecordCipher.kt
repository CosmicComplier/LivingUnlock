package com.windowslockpin.companion.core.storage

import com.windowslockpin.companion.core.crypto.CryptoEngine
import java.security.SecureRandom

interface RecordCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(ciphertextWithIv: ByteArray, aad: ByteArray): ByteArray
}

class JvmRecordCipher(private val wrappingKey: ByteArray) : RecordCipher {
    init {
        require(wrappingKey.size == 32) { "Wrapping key must be exactly 32 bytes for AES-256" }
    }

    private val secureRandom = SecureRandom()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val ciphertext = CryptoEngine.encryptAesGcm(wrappingKey, iv, plaintext, aad)
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    override fun decrypt(ciphertextWithIv: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertextWithIv.size >= 12 + 16) { "Ciphertext must contain at least 12-byte IV and 16-byte GCM tag" }
        val iv = ciphertextWithIv.copyOfRange(0, 12)
        val ciphertext = ciphertextWithIv.copyOfRange(12, ciphertextWithIv.size)
        return CryptoEngine.decryptAesGcm(wrappingKey, iv, ciphertext, aad)
    }
}
