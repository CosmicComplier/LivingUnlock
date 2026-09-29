package com.windowslockpin.companion.core.crypto
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object DeviceInfoEnvelope {
    private val magic = "LUDI".toByteArray(Charsets.US_ASCII)
    fun decrypt(payload: ByteArray, pairingKey: ByteArray): String {
        require(payload.size in 33..8176 && pairingKey.size == 32)
        require(payload.copyOfRange(0,4).contentEquals(magic))
        val key = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(pairingKey,"HmacSHA256"))
            doFinal("LivingUnlock device info v1".toByteArray(Charsets.US_ASCII))
        }
        var plain: ByteArray? = null
        try {
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,payload.copyOfRange(4,16)))
            c.updateAAD(magic); plain=c.doFinal(payload.copyOfRange(16,payload.size))
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
        } finally { key.fill(0);plain?.fill(0) }
    }
}
