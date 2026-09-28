package com.windowslockpin.companion.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.windowslockpin.companion.core.storage.RecordCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreRecordCipher(
    private val alias: String = DEFAULT_ALIAS
) : RecordCipher {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv ?: error("Cipher IV must not be null after initialization")
        require(iv.size == IV_SIZE) { "Expected IV size $IV_SIZE, got ${iv.size}" }
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun decrypt(ciphertextWithIv: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertextWithIv.size >= IV_SIZE + TAG_BITS / 8) { "Encrypted record is too short" }
        val iv = ciphertextWithIv.copyOfRange(0, IV_SIZE)
        val ciphertext = ciphertextWithIv.copyOfRange(IV_SIZE, ciphertextWithIv.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val DEFAULT_ALIAS = "wslp_pair_record_wrap_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
