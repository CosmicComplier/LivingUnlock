package com.windowslockpin.companion.core.model

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream

data class FrameHeader(
    val version: Byte = ProtocolConstants.PROTOCOL_VERSION,
    val messageType: Byte,
    val reserved: Short = 0,
    val requestId: RequestId,
    val payloadLength: Int
) {
    init {
        require(version == ProtocolConstants.PROTOCOL_VERSION) {
            "Unsupported frame version: $version"
        }
        require(payloadLength in 0..ProtocolConstants.MAX_PAYLOAD_SIZE) {
            "Payload length $payloadLength exceeds maximum allowed ${ProtocolConstants.MAX_PAYLOAD_SIZE}"
        }
        require(reserved.toInt() == 0) { "Reserved frame flags must be zero in protocol v1" }
    }

    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream(ProtocolConstants.HEADER_SIZE)
        val dos = DataOutputStream(baos)
        dos.writeByte(ProtocolConstants.MAGIC_BYTE_0.toInt())
        dos.writeByte(ProtocolConstants.MAGIC_BYTE_1.toInt())
        dos.writeByte(version.toInt())
        dos.writeByte(messageType.toInt())
        dos.writeShort(reserved.toInt())
        dos.writeLong(requestId.value)
        dos.writeShort(payloadLength)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): FrameHeader {
            require(bytes.size >= ProtocolConstants.HEADER_SIZE) {
                "Frame header requires at least ${ProtocolConstants.HEADER_SIZE} bytes, got ${bytes.size}"
            }
            val dis = DataInputStream(bytes.inputStream())
            val m0 = dis.readByte()
            val m1 = dis.readByte()
            if (m0 != ProtocolConstants.MAGIC_BYTE_0 || m1 != ProtocolConstants.MAGIC_BYTE_1) {
                throw BadMagicException("Invalid magic bytes: 0x%02X 0x%02X".format(m0, m1))
            }
            val ver = dis.readByte()
            if (ver != ProtocolConstants.PROTOCOL_VERSION) {
                throw BadVersionException("Unsupported protocol version: $ver, expected ${ProtocolConstants.PROTOCOL_VERSION}")
            }
            val type = dis.readByte()
            val res = dis.readShort()
            if (res.toInt() != 0) {
                throw BadReservedFlagsException("Reserved frame flags must be zero in protocol v1")
            }
            val reqId = dis.readLong()
            val len = dis.readUnsignedShort()

            if (len > ProtocolConstants.MAX_PAYLOAD_SIZE) {
                throw FrameOversizeException("Frame payload length $len exceeds max ${ProtocolConstants.MAX_PAYLOAD_SIZE}")
            }

            return FrameHeader(
                version = ver,
                messageType = type,
                reserved = res,
                requestId = RequestId(reqId),
                payloadLength = len
            )
        }
    }
}

data class Frame(
    val header: FrameHeader,
    val payload: ByteArray
) {
    init {
        require(payload.size == header.payloadLength) {
            "Payload size ${payload.size} does not match header length ${header.payloadLength}"
        }
        val totalSize = ProtocolConstants.HEADER_SIZE + payload.size
        require(totalSize <= ProtocolConstants.MAX_FRAME_SIZE) {
            "Total frame size $totalSize exceeds maximum ${ProtocolConstants.MAX_FRAME_SIZE}"
        }
    }

    fun toByteArray(): ByteArray {
        val total = ByteArray(ProtocolConstants.HEADER_SIZE + payload.size)
        System.arraycopy(header.toByteArray(), 0, total, 0, ProtocolConstants.HEADER_SIZE)
        System.arraycopy(payload, 0, total, ProtocolConstants.HEADER_SIZE, payload.size)
        return total
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        if (header != other.header) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun create(messageType: Byte, requestId: RequestId, payload: ByteArray): Frame {
            if (payload.size > ProtocolConstants.MAX_PAYLOAD_SIZE) {
                throw FrameOversizeException("Payload size ${payload.size} exceeds maximum ${ProtocolConstants.MAX_PAYLOAD_SIZE}")
            }
            val header = FrameHeader(
                version = ProtocolConstants.PROTOCOL_VERSION,
                messageType = messageType,
                reserved = 0,
                requestId = requestId,
                payloadLength = payload.size
            )
            return Frame(header, payload)
        }

        fun decode(bytes: ByteArray): Frame {
            if (bytes.size < ProtocolConstants.HEADER_SIZE) {
                throw MalformedFrameException("Byte array smaller than header size: ${bytes.size}")
            }
            if (bytes.size > ProtocolConstants.MAX_FRAME_SIZE) {
                throw FrameOversizeException("Frame size ${bytes.size} exceeds max ${ProtocolConstants.MAX_FRAME_SIZE}")
            }
            val header = FrameHeader.fromByteArray(bytes)
            val expectedTotal = ProtocolConstants.HEADER_SIZE + header.payloadLength
            if (bytes.size != expectedTotal) {
                throw MalformedFrameException("Frame total size ${bytes.size} does not match header length ${expectedTotal}")
            }
            val payload = ByteArray(header.payloadLength)
            System.arraycopy(bytes, ProtocolConstants.HEADER_SIZE, payload, 0, header.payloadLength)
            return Frame(header, payload)
        }

        fun readFromStream(inputStream: InputStream): Frame {
            val headerBytes = ByteArray(ProtocolConstants.HEADER_SIZE)
            var read = 0
            while (read < ProtocolConstants.HEADER_SIZE) {
                val count = inputStream.read(headerBytes, read, ProtocolConstants.HEADER_SIZE - read)
                if (count == -1) {
                    if (read == 0) throw java.io.EOFException("End of stream reached")
                    throw MalformedFrameException("Stream ended before header was completely read")
                }
                read += count
            }
            val header = FrameHeader.fromByteArray(headerBytes)
            val payload = ByteArray(header.payloadLength)
            var pRead = 0
            while (pRead < header.payloadLength) {
                val count = inputStream.read(payload, pRead, header.payloadLength - pRead)
                if (count == -1) {
                    throw MalformedFrameException("Stream ended before payload was completely read")
                }
                pRead += count
            }
            return Frame(header, payload)
        }
    }
}

open class ProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class BadMagicException(message: String) : ProtocolException(message)
class BadVersionException(message: String) : ProtocolException(message)
class BadReservedFlagsException(message: String) : ProtocolException(message)
class FrameOversizeException(message: String) : ProtocolException(message)
class MalformedFrameException(message: String, cause: Throwable? = null) : ProtocolException(message, cause)
