package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.crypto.CryptoEngine
import com.windowslockpin.companion.core.crypto.FakeSecretStore
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoEngineTest {

    @Test
    fun testHkdfSha256Rfc5869TestCase1() {
        // RFC 5869 Test Case 1
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val l = 42

        val expectedPrk = hexToBytes("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val expectedOkm = hexToBytes("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

        val prk = CryptoEngine.hkdfExtract(salt, ikm)
        assertArrayEquals("PRK must match RFC 5869", expectedPrk, prk)

        val okm = CryptoEngine.hkdfExpand(prk, info, l)
        assertArrayEquals("OKM must match RFC 5869", expectedOkm, okm)

        val directOkm = CryptoEngine.hkdf(salt, ikm, info, l)
        assertArrayEquals("Combined HKDF must match RFC 5869", expectedOkm, directOkm)
    }

    @Test
    fun testAes256GcmRoundtripAndTamperResistance() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val iv = ByteArray(12) { (it * 11).toByte() }
        val plaintext = "Secret biometric confirmation token 2026".toByteArray()
        val aad = "WindowsLockPin-AAD".toByteArray()

        val ciphertext = CryptoEngine.encryptAesGcm(key, iv, plaintext, aad)
        assertFalse(plaintext.contentEquals(ciphertext))

        val decrypted = CryptoEngine.decryptAesGcm(key, iv, ciphertext, aad)
        assertArrayEquals(plaintext, decrypted)

        // Tamper with ciphertext byte
        val tamperedCiphertext = ciphertext.clone()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

        assertThrows(AEADBadTagException::class.java) {
            CryptoEngine.decryptAesGcm(key, iv, tamperedCiphertext, aad)
        }

        // Tamper with AAD
        val tamperedAad = "WindowsLockPin-BAD".toByteArray()
        assertThrows(AEADBadTagException::class.java) {
            CryptoEngine.decryptAesGcm(key, iv, ciphertext, tamperedAad)
        }
    }

    @Test
    fun testFakeSecretStoreEcSigningAndVerification() {
        val store = FakeSecretStore()
        val alias = "companion_test_key"
        val kp = store.getOrCreateCompanionKeyPair(alias)
        assertNotNull(kp.public)
        assertNotNull(kp.private)

        val transcript = "WSLP-TEST-CANONICAL-TRANSCRIPT-BYTES".toByteArray()
        val signer = store.initSignature(alias)
        signer.update(transcript)
        val signature = signer.sign()
        assertTrue(signature.isNotEmpty())

        val valid = store.verifyTranscript(kp.public, transcript, signature)
        assertTrue("Signature must verify against transcript", valid)

        // Modified transcript fails verification
        val modifiedTranscript = "WSLP-TEST-TAMPERED-TRANSCRIPT-BYTES".toByteArray()
        val invalid = store.verifyTranscript(kp.public, modifiedTranscript, signature)
        assertFalse("Tampered transcript must fail verification", invalid)
    }

    @Test
    fun testEcdhSharedSecretAgreement() {
        val storePhone = FakeSecretStore()
        val storeHost = FakeSecretStore()

        val phoneKp = storePhone.getOrCreateCompanionKeyPair("phone")
        val hostKp = storeHost.getOrCreateCompanionKeyPair("host")

        val secretPhone = storePhone.deriveSharedSecret("phone", hostKp.public)
        val secretHost = storeHost.deriveSharedSecret("host", phoneKp.public)

        assertArrayEquals("ECDH shared secret derived on both ends must match", secretPhone, secretHost)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
