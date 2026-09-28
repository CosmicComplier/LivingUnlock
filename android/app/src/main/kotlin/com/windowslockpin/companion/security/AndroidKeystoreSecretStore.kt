package com.windowslockpin.companion.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.windowslockpin.companion.core.crypto.SecretStore
import com.windowslockpin.companion.core.model.SafeLogger
import java.security.*
import java.security.spec.ECGenParameterSpec

class AndroidKeystoreSecretStore(
    private val requireBiometricAuthPerUse: Boolean = true
) : SecretStore {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    override fun getOrCreateCompanionKeyPair(alias: String): KeyPair {
        val existingPublic = getPublicKey(alias)
        val existingPrivate = keyStore.getKey(alias, null) as? PrivateKey
        if (existingPublic != null && existingPrivate != null) {
            return KeyPair(existingPublic, existingPrivate)
        }

        return generateKeyPair(alias)
    }

    override fun getPublicKey(alias: String): PublicKey? {
        return keyStore.getCertificate(alias)?.publicKey
    }

    override fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            SafeLogger.i(TAG, "Deleted companion key")
        }
    }

    override fun initSignature(alias: String): Signature {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Private key not found for alias: $alias")
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        return signature
    }

    override fun verifyTranscript(publicKey: PublicKey, transcript: ByteArray, signature: ByteArray): Boolean {
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(transcript)
        return try {
            verifier.verify(signature)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Signature verification error", e)
            false
        }
    }

    override fun deriveSharedSecret(alias: String, peerPublicKey: PublicKey): ByteArray {
        throw UnsupportedOperationException(
            "Android Keystore ECDH is not enabled in Phase 1; pairing authentication uses the QR pairing token"
        )
    }

    private fun generateKeyPair(alias: String): KeyPair {
        // Attempt StrongBox first, then standard TEE fallback
        return try {
            generateKeyPairInternal(alias, useStrongBox = true)
        } catch (e: Exception) {
            SafeLogger.w(TAG, "StrongBox key generation failed or unsupported, falling back to standard TEE: ${e.message}")
            generateKeyPairInternal(alias, useStrongBox = false)
        }
    }

    private fun generateKeyPairInternal(alias: String, useStrongBox: Boolean): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)

        val purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        val builder = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)

        if (requireBiometricAuthPerUse) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0, // 0 timeout means auth-per-use (requires biometric prompt on every invocation)
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1) // -1 indicates auth-per-use on API < 30
            }
        }

        if (useStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        kpg.initialize(builder.build())
        val keyPair = kpg.generateKeyPair()
        SafeLogger.i(TAG, "Generated companion EC key pair (StrongBox: $useStrongBox, auth-per-use: $requireBiometricAuthPerUse)")
        return keyPair
    }

    companion object {
        private const val TAG = "AndroidKeystoreSecretStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
