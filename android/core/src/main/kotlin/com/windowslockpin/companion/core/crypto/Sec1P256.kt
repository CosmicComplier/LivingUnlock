package com.windowslockpin.companion.core.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

object Sec1P256 {
    const val UNCOMPRESSED_KEY_SIZE = 65
    const val COORDINATE_SIZE = 32
    const val UNCOMPRESSED_PREFIX: Byte = 0x04

    // NIST P-256 (secp256r1 / prime256v1) curve parameters
    // p = 2^256 - 2^224 + 2^192 + 2^96 - 1
    val P: BigInteger = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    // a = -3 mod p = p - 3
    val A: BigInteger = P.subtract(BigInteger.valueOf(3))
    // b = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
    val B: BigInteger = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)

    private val ecParameterSpec: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    private val keyFactory: KeyFactory by lazy {
        KeyFactory.getInstance("EC")
    }

    fun encodePublicKey(publicKey: ECPublicKey): ByteArray {
        val point = publicKey.w
        val xBytes = point.affineX.toUnsigned32Bytes()
        val yBytes = point.affineY.toUnsigned32Bytes()

        val encoded = ByteArray(UNCOMPRESSED_KEY_SIZE)
        encoded[0] = UNCOMPRESSED_PREFIX
        System.arraycopy(xBytes, 0, encoded, 1, COORDINATE_SIZE)
        System.arraycopy(yBytes, 0, encoded, 1 + COORDINATE_SIZE, COORDINATE_SIZE)
        return encoded
    }

    fun decodePublicKey(bytes: ByteArray): ECPublicKey {
        if (bytes.size != UNCOMPRESSED_KEY_SIZE) {
            throw IllegalArgumentException("SEC1 P-256 uncompressed public key must be exactly $UNCOMPRESSED_KEY_SIZE bytes, got ${bytes.size}")
        }
        if (bytes[0] != UNCOMPRESSED_PREFIX) {
            throw IllegalArgumentException("SEC1 uncompressed public key must start with 0x04 prefix, got 0x%02X".format(bytes[0]))
        }

        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_SIZE, UNCOMPRESSED_KEY_SIZE))

        if (!isOnCurve(x, y)) {
            throw IllegalArgumentException("Invalid SEC1 P-256 public key: point is not on curve secp256r1")
        }

        val point = ECPoint(x, y)
        val spec = ECPublicKeySpec(point, ecParameterSpec)
        return keyFactory.generatePublic(spec) as ECPublicKey
    }

    fun isValidSec1P256(bytes: ByteArray): Boolean {
        if (bytes.size != UNCOMPRESSED_KEY_SIZE) return false
        if (bytes[0] != UNCOMPRESSED_PREFIX) return false
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_SIZE, UNCOMPRESSED_KEY_SIZE))
        return isOnCurve(x, y)
    }

    fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x <= BigInteger.ZERO || x >= P || y <= BigInteger.ZERO || y >= P) {
            return false
        }
        // y^2 mod p == (x^3 + ax + b) mod p
        val y2 = y.modPow(BigInteger.valueOf(2), P)
        val x3 = x.modPow(BigInteger.valueOf(3), P)
        val ax = A.multiply(x).mod(P)
        val rhs = x3.add(ax).add(B).mod(P)
        return y2 == rhs
    }

    private fun BigInteger.toUnsigned32Bytes(): ByteArray {
        val raw = this.toByteArray()
        val out = ByteArray(COORDINATE_SIZE)
        if (raw.size >= COORDINATE_SIZE) {
            System.arraycopy(raw, raw.size - COORDINATE_SIZE, out, 0, COORDINATE_SIZE)
        } else {
            System.arraycopy(raw, 0, out, COORDINATE_SIZE - raw.size, raw.size)
        }
        return out
    }
}
