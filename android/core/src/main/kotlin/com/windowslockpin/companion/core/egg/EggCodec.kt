package com.windowslockpin.companion.core.egg

import java.security.SecureRandom
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Independent Easter-egg format. Never accepts pairing keys or pairing records. */
object EggCodec {
    const val PREFIX = "livingunlock:egg:"
    const val MAX_PAYLOAD_BYTES = 1200
    const val MAX_TEXT_BYTES = MAX_PAYLOAD_BYTES
    private const val MAX_CODE_CHARS = 2400
    private val typedPayloadMagic = byteArrayOf(0x4c, 0x55, 0x45, 0x32)
    private val random = SecureRandom()
    // Intentionally public app-wide easter-egg key, not suitable for private secrets.
    private val publicEggKey = byteArrayOf(29, 82, 113, -7, 44, -101, 2, 99, 16, 78, -31, 7, 104, -52, 60, 12,
        -17, 41, 71, 117, -6, 92, 5, -75, 67, 24, -116, 33, 86, -12, 40, 101)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    data class Envelope(val mode: String, val salt: ByteArray, val nonce: ByteArray, val cipher: ByteArray, val aad: ByteArray)
    enum class MediaKind { IMAGE, VIDEO }
    sealed class Payload {
        data class Text(val text: String) : Payload()
        data class Image(val mimeType: String, val bytes: ByteArray) : Payload()
        data class MediaUrl(val kind: MediaKind, val url: String) : Payload()
    }
    fun isEgg(raw: String) = raw.startsWith(PREFIX, ignoreCase = true)

    fun parse(raw: String): Envelope {
        require(raw.length <= MAX_CODE_CHARS) { "彩蛋二维码过长" }
        val fields = raw.split('.')
        require(fields.size == 6 && fields[0] == "${PREFIX}1") { "无法识别的彩蛋格式" }
        val mode = fields[1]
        require(mode in setOf("A7", "C4", "P9")) { "不支持的彩蛋版本或算法编号" }
        require(fields[2] == if (mode == "P9") "P0" else "K1") { "未知彩蛋密钥编号" }
        fun decode(s: String): ByteArray {
            require(s.matches(Regex("[A-Za-z0-9_-]+"))) { "彩蛋编码无效" }
            val b = decoder.decode(s)
            require(encoder.encodeToString(b) == s) { "彩蛋编码不规范" }
            return b
        }
        val salt = if (fields[3] == "-") byteArrayOf() else decode(fields[3])
        require(salt.size == if (mode == "P9") 16 else 0) { "彩蛋参数无效" }
        val nonce = decode(fields[4]); require(nonce.size == 12) { "彩蛋参数无效" }
        val cipher = decode(fields[5]); require(cipher.size in 17..MAX_PAYLOAD_BYTES + 16) { "彩蛋正文长度无效" }
        return Envelope(mode, salt, nonce, cipher, fields.take(5).joinToString(".").toByteArray(Charsets.US_ASCII))
    }

    private fun key(mode: String, salt: ByteArray, password: CharArray?): ByteArray {
        if (mode != "P9") return publicEggKey.copyOf()
        require(password != null && password.isNotEmpty() && password.size <= 256) { "请输入彩蛋口令" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
            .withMemoryAsKB(32768).withIterations(3).withParallelism(1).build()
        return ByteArray(32).also { output ->
            val generator = Argon2BytesGenerator(); generator.init(parameters)
            try { generator.generateBytes(password, output) } finally { parameters.clear() }
        }
    }

    private fun cipher(mode: String, operation: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        val chacha = mode == "C4"
        return Cipher.getInstance(if (chacha) "ChaCha20-Poly1305" else "AES/GCM/NoPadding").apply {
            init(operation, SecretKeySpec(key, if (chacha) "ChaCha20" else "AES"),
                if (chacha) IvParameterSpec(nonce) else GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    private fun validateMediaUrl(url: String): String {
        require(url.length <= 2048) { "媒体网址过长" }
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "媒体网址必须是有效的 HTTPS 地址"
        }
        return url
    }

    private fun decodePayload(plain: ByteArray): Payload {
        if (plain.size < 5 || !plain.copyOfRange(0, 4).contentEquals(typedPayloadMagic)) {
            return Payload.Text(decodeUtf8(plain))
        }
        val content = plain.copyOfRange(5, plain.size)
        return when (plain[4].toInt()) {
            1 -> {
                require(content.size >= 4 && content[0] == 0xff.toByte() && content[1] == 0xd8.toByte()) { "JPEG 图片数据无效" }
                Payload.Image("image/jpeg", content)
            }
            2 -> {
                val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                require(content.size >= png.size && content.copyOfRange(0, png.size).contentEquals(png)) { "PNG 图片数据无效" }
                Payload.Image("image/png", content)
            }
            3 -> Payload.MediaUrl(MediaKind.IMAGE, validateMediaUrl(decodeUtf8(content)))
            4 -> Payload.MediaUrl(MediaKind.VIDEO, validateMediaUrl(decodeUtf8(content)))
            else -> throw IllegalArgumentException("不支持的彩蛋内容类型")
        }
    }

    fun decryptPayload(raw: String, password: CharArray? = null): Payload {
        val e = parse(raw); val key = key(e.mode, e.salt, password)
        var plain: ByteArray? = null
        try {
            plain = cipher(e.mode, Cipher.DECRYPT_MODE, key, e.nonce, e.aad).doFinal(e.cipher)
            return decodePayload(plain)
        } finally { key.fill(0); plain?.fill(0) }
    }

    fun decrypt(raw: String, password: CharArray? = null): String = when (val payload = decryptPayload(raw, password)) {
        is Payload.Text -> payload.text
        is Payload.MediaUrl -> payload.url
        is Payload.Image -> throw IllegalArgumentException("此彩蛋包含图片")
    }

    private fun encodePayload(payload: Payload): ByteArray = when (payload) {
        is Payload.Text -> payload.text.toByteArray(Charsets.UTF_8)
        is Payload.Image -> {
            val type = when (payload.mimeType.lowercase()) {
                "image/jpeg" -> 1
                "image/png" -> 2
                else -> throw IllegalArgumentException("只支持 JPEG 或 PNG 图片")
            }
            typedPayloadMagic + byteArrayOf(type.toByte()) + payload.bytes
        }
        is Payload.MediaUrl -> {
            val type = if (payload.kind == MediaKind.IMAGE) 3 else 4
            typedPayloadMagic + byteArrayOf(type.toByte()) + validateMediaUrl(payload.url).toByteArray(Charsets.UTF_8)
        }
    }

    fun encryptPayload(mode: String, payload: Payload, password: CharArray? = null): String =
        encryptBytes(mode, encodePayload(payload), password)

    fun encrypt(mode: String, text: String, password: CharArray? = null): String =
        encryptBytes(mode, text.toByteArray(Charsets.UTF_8), password)

    private fun encryptBytes(mode: String, plain: ByteArray, password: CharArray?): String {
        require(mode in setOf("A7", "C4", "P9"))
        require(plain.size in 1..MAX_PAYLOAD_BYTES) { "内容须为 1–1200 个字节" }
        val nonce = ByteArray(12).also(random::nextBytes)
        val salt = if (mode == "P9") ByteArray(16).also(random::nextBytes) else byteArrayOf()
        val header = "${PREFIX}1.$mode.${if (mode == "P9") "P0" else "K1"}.${if (salt.isEmpty()) "-" else encoder.encodeToString(salt)}.${encoder.encodeToString(nonce)}"
        val key = key(mode, salt, password)
        try {
            return header + "." + encoder.encodeToString(cipher(mode, Cipher.ENCRYPT_MODE, key, nonce,
                header.toByteArray(Charsets.US_ASCII)).doFinal(plain))
        } finally { key.fill(0); plain.fill(0) }
    }
}
