package com.windowslockpin.companion.core.crypto

import java.security.KeyPair
import java.security.PublicKey
import java.security.Signature

interface SecretStore {
    fun getOrCreateCompanionKeyPair(alias: String): KeyPair
    fun getPublicKey(alias: String): PublicKey?
    fun deleteKey(alias: String)
    fun initSignature(alias: String): Signature
    fun verifyTranscript(publicKey: PublicKey, transcript: ByteArray, signature: ByteArray): Boolean
    fun deriveSharedSecret(alias: String, peerPublicKey: PublicKey): ByteArray
}
