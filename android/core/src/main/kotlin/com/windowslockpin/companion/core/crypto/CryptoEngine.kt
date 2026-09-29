package com.windowslockpin.companion.core.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {
    private const val HKDF_ALGORITHM = "HmacSHA256"
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HKDF_ALGORITHM)
        val key = if (salt.isEmpty()) {
            SecretKeySpec(ByteArray(32), HKDF_ALGORITHM)
        } else {
            SecretKeySpec(salt, HKDF_ALGORITHM)
        }
        mac.init(key)
        return mac.doFinal(ikm)
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen in 1..(255 * 32)) { "outLen out of range for HKDF-SHA256" }
        val mac = Mac.getInstance(HKDF_ALGORITHM)
        mac.init(SecretKeySpec(prk, HKDF_ALGORITHM))

        val result = ByteArray(outLen)
        var t = ByteArray(0)
        var generated = 0
        var round = 1.toByte()

        while (generated < outLen) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(round)
            t = mac.doFinal()

            val toCopy = Math.min(t.size, outLen - generated)
            System.arraycopy(t, 0, result, generated, toCopy)
            generated += toCopy
            round++
        }
        return result
    }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, outLen)
    }

    fun encryptAesGcm(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "AES-256 requires a 32-byte key" }
        require(iv.size == 12) { "AES-GCM standard IV requires 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    fun decryptAesGcm(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "AES-256 requires a 32-byte key" }
        require(iv.size == 12) { "AES-GCM standard IV requires 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HKDF_ALGORITHM)
        mac.init(SecretKeySpec(key, HKDF_ALGORITHM))
        return mac.doFinal(data)
    }

    fun zeroize(bytes: ByteArray?) {
        bytes?.fill(0.toByte())
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)
}
