package com.windowslockpin.companion.core.crypto

import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyAgreement

class FakeSecretStore : SecretStore {
    private val keyStore = ConcurrentHashMap<String, KeyPair>()

    override fun getOrCreateCompanionKeyPair(alias: String): KeyPair {
        return keyStore.computeIfAbsent(alias) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            kpg.generateKeyPair()
        }
    }

    override fun getPublicKey(alias: String): PublicKey? {
        return keyStore[alias]?.public
    }

    override fun deleteKey(alias: String) {
        keyStore.remove(alias)
    }

    override fun initSignature(alias: String): Signature {
        val kp = keyStore[alias] ?: throw IllegalStateException("Key alias not found: $alias")
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        return sig
    }

    override fun verifyTranscript(publicKey: PublicKey, transcript: ByteArray, signature: ByteArray): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(transcript)
        return try {
            verifier.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    override fun deriveSharedSecret(alias: String, peerPublicKey: PublicKey): ByteArray {
        val kp = keyStore[alias] ?: throw IllegalStateException("Key alias not found: $alias")
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp.private)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }
}
