package com.windowslockpin.companion.core.model

enum class ProtocolErrorCode(val code: Int, val description: String) {
    BAD_MAGIC(0x0001, "Invalid frame magic bytes"),
    BAD_VERSION(0x0002, "Unsupported protocol version"),
    UNKNOWN_MESSAGE_TYPE(0x0003, "Unknown message type"),
    FRAME_OVERSIZE(0x0004, "Frame exceeds maximum allowed size"),
    MALFORMED_PAYLOAD(0x0005, "Malformed message payload"),
    REPLAY_DETECTED(0x0006, "Replayed challenge nonce detected"),
    EXPIRED(0x0007, "Message or pairing token has expired"),
    UNEXPECTED_STATE(0x0008, "Message not allowed in current state"),
    CRYPTO_FAILURE(0x0009, "Cryptographic operation failed"),
    DEVICE_MISMATCH(0x000A, "PC or device identity does not match paired record");

    companion object {
        fun fromCode(code: Int): ProtocolErrorCode? =
            values().firstOrNull { it.code == code }
    }
}

sealed class ProtocolResult<out T> {
    data class Success<out T>(val value: T) : ProtocolResult<T>()
    data class Failure(val errorCode: ProtocolErrorCode, val message: String, val cause: Throwable? = null) : ProtocolResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw ProtocolException("$errorCode: $message", cause)
    }
}
