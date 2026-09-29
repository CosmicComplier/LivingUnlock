package com.windowslockpin.companion.core.model

import com.windowslockpin.companion.core.crypto.CryptoEngine
import java.util.Base64
import java.util.regex.Pattern

@JvmInline
value class PcId(val value: String) {
    init {
        require(value.length in ProtocolConstants.MIN_IDENTIFIER_LENGTH..ProtocolConstants.MAX_IDENTIFIER_LENGTH) {
            "PcId length must be between ${ProtocolConstants.MIN_IDENTIFIER_LENGTH} and ${ProtocolConstants.MAX_IDENTIFIER_LENGTH}, got ${value.length}"
        }
        require(HEX_PATTERN.matcher(value).matches()) {
            "PcId must be valid hexadecimal characters only"
        }
    }

    companion object {
        private val HEX_PATTERN: Pattern = Pattern.compile("^[0-9a-fA-F]+$")

        fun fromString(raw: String): PcId {
            val trimmed = raw.trim().lowercase()
            return PcId(trimmed)
        }
    }
}

@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotEmpty() && value.length <= ProtocolConstants.MAX_IDENTIFIER_LENGTH) {
            "DeviceId length must be between 1 and ${ProtocolConstants.MAX_IDENTIFIER_LENGTH}, got ${value.length}"
        }
        require(DEVICE_ID_PATTERN.matcher(value).matches()) {
            "DeviceId contains invalid characters"
        }
    }

    override fun toString(): String = "[REDACTED_DEVICE_ID]"

    companion object {
        private val DEVICE_ID_PATTERN: Pattern = Pattern.compile("^[0-9a-zA-Z._-]+$")
    }
}

@JvmInline
value class RequestId(val value: Long) {
    companion object {
        fun generate(): RequestId {
            val random = java.security.SecureRandom()
            var result: Long
            do {
                val randomBytes = ByteArray(8)
                random.nextBytes(randomBytes)
                result = 0L
                for (b in randomBytes) result = (result shl 8) or (b.toLong() and 0xFFL)
            } while (result == 0L)
            return RequestId(result)
        }
    }
}

class PairingToken(val bytes: ByteArray) {
    init {
        require(bytes.size in 16..32) {
            "PairingToken must be 16 to 32 bytes, got ${bytes.size}"
        }
    }

    fun toBase64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun zeroize() {
        CryptoEngine.zeroize(bytes)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingToken) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "[REDACTED_PAIRING_TOKEN]"

    companion object {
        fun fromBase64Url(raw: String): PairingToken {
            require(raw.length in 22..43) {
                "PairingToken Base64URL string must be 22 to 43 chars, got ${raw.length}"
            }
            // Strict check for Base64URL characters without padding
            require(BASE64_URL_PATTERN.matcher(raw).matches()) {
                "PairingToken contains invalid Base64URL characters or padding"
            }
            val decoded = try {
                Base64.getUrlDecoder().decode(raw)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Malformed Base64URL in pairing token", e)
            }
            return PairingToken(decoded)
        }

        private val BASE64_URL_PATTERN: Pattern = Pattern.compile("^[0-9a-zA-Z_-]+$")
    }
}

class ChallengeNonce(val bytes: ByteArray) {
    init {
        require(bytes.size in 16..32) {
            "ChallengeNonce must be 16 to 32 bytes, got ${bytes.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChallengeNonce) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "[REDACTED_CHALLENGE_NONCE]"

    companion object {
        fun generate(size: Int = 32): ChallengeNonce {
            require(size in 16..32)
            val bytes = ByteArray(size)
            java.security.SecureRandom().nextBytes(bytes)
            return ChallengeNonce(bytes)
        }
    }
}

@JvmInline
value class BluetoothMacAddress(val value: String) {
    init {
        require(MAC_PATTERN.matcher(value).matches()) {
            "Invalid Bluetooth MAC address format: must be XX:XX:XX:XX:XX:XX uppercase hex"
        }
    }

    override fun toString(): String = "XX:XX:XX:${value.takeLast(8)}"

    companion object {
        private val MAC_PATTERN: Pattern = Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun parse(raw: String): BluetoothMacAddress {
            val upper = raw.trim().uppercase()
            return BluetoothMacAddress(upper)
        }
    }
}
