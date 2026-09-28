package com.windowslockpin.companion.core.model

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PairingUri(
    val version: Int,
    val pcId: PcId,
    val pcName: String,
    val bluetoothMac: BluetoothMacAddress,
    val pairingToken: PairingToken,
    val expiryEpochSec: Long
) {
    fun isExpired(currentEpochSec: Long): Boolean = currentEpochSec > expiryEpochSec

    fun toUriString(): String {
        val encodedName = java.net.URLEncoder.encode(pcName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "${ProtocolConstants.PAIRING_URI_SCHEME}://${ProtocolConstants.PAIRING_URI_HOST}?" +
                "v=$version" +
                "&pc_id=${pcId.value}" +
                "&pc_name=$encodedName" +
                "&bt_mac=${bluetoothMac.value}" +
                "&pair_token=${pairingToken.toBase64Url()}" +
                "&exp=$expiryEpochSec"
    }

    override fun toString(): String {
        // Redact pairing token and full pcId in string representation
        val maskedPcId = if (pcId.value.length > 8) {
            "${pcId.value.take(4)}...${pcId.value.takeLast(4)}"
        } else pcId.value
        return "PairingUri(v=$version, pcId=$maskedPcId, pcName='$pcName', btMac=$bluetoothMac, exp=$expiryEpochSec, token=[REDACTED])"
    }

    companion object {
        private val ALLOWED_PARAMS = setOf("v", "pc_id", "pc_name", "bt_mac", "pair_token", "exp")
        private val SAFE_PC_NAME_REGEX = Regex("^[a-zA-Z0-9 _.-]{1,64}$")

        fun parse(rawUri: String, currentEpochSec: Long? = null): PairingUri {
            if (rawUri.length > ProtocolConstants.MAX_URI_LENGTH) {
                throw MalformedPairingUriException("URI exceeds maximum length of ${ProtocolConstants.MAX_URI_LENGTH} bytes (was ${rawUri.length})")
            }

            val questionIndex = rawUri.indexOf('?')
            if (questionIndex == -1) {
                throw MalformedPairingUriException("Missing query string delimiter '?' in URI")
            }

            val prefix = rawUri.substring(0, questionIndex)
            val queryString = rawUri.substring(questionIndex + 1)

            // Validate scheme and host/path
            // Expected: wslp://pair or wslp:pair
            val normalizedPrefix = prefix.lowercase()
            val expectedFull = "${ProtocolConstants.PAIRING_URI_SCHEME}://${ProtocolConstants.PAIRING_URI_HOST}"
            val expectedOpaque = "${ProtocolConstants.PAIRING_URI_SCHEME}:${ProtocolConstants.PAIRING_URI_HOST}"
            if (normalizedPrefix != expectedFull && normalizedPrefix != expectedOpaque) {
                throw MalformedPairingUriException("Invalid URI scheme or host: expected '$expectedFull', got '$prefix'")
            }

            val paramPairs = queryString.split('&')
            val seenKeys = mutableSetOf<String>()
            val paramMap = mutableMapOf<String, String>()

            for (pair in paramPairs) {
                if (pair.isEmpty()) {
                    throw MalformedPairingUriException("Empty query parameter in URI")
                }
                val eqIdx = pair.indexOf('=')
                if (eqIdx == -1) {
                    throw MalformedPairingUriException("Query parameter missing '=': '$pair'")
                }
                val key = pair.substring(0, eqIdx)
                val rawValue = pair.substring(eqIdx + 1)

                if (!ALLOWED_PARAMS.contains(key)) {
                    throw MalformedPairingUriException("Unknown query parameter '$key' is not allowed in v1")
                }
                if (!seenKeys.add(key)) {
                    throw MalformedPairingUriException("Duplicate query parameter '$key' is not allowed")
                }
                paramMap[key] = rawValue
            }

            // Verify all required parameters exist
            for (required in ALLOWED_PARAMS) {
                if (!paramMap.containsKey(required)) {
                    throw MalformedPairingUriException("Missing required query parameter '$required'")
                }
            }

            // 1. Version
            val versionStr = paramMap["v"]!!
            val version = versionStr.toIntOrNull()
                ?: throw MalformedPairingUriException("Version must be an integer, got '$versionStr'")
            if (version != ProtocolConstants.PROTOCOL_VERSION.toInt()) {
                throw MalformedPairingUriException("Unsupported protocol version $version; expected ${ProtocolConstants.PROTOCOL_VERSION}")
            }

            // 2. PC ID
            val pcId = try {
                PcId.fromString(paramMap["pc_id"]!!)
            } catch (e: IllegalArgumentException) {
                throw MalformedPairingUriException("Invalid pc_id: ${e.message}", e)
            }

            // 3. PC Name
            val rawName = paramMap["pc_name"]!!
            val decodedName = try {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            } catch (e: Exception) {
                throw MalformedPairingUriException("Failed to URL-decode pc_name: ${e.message}", e)
            }
            if (!SAFE_PC_NAME_REGEX.matches(decodedName)) {
                throw MalformedPairingUriException("pc_name contains disallowed characters or invalid length: '$decodedName'")
            }

            // 4. Bluetooth MAC
            val mac = try {
                BluetoothMacAddress.parse(paramMap["bt_mac"]!!)
            } catch (e: IllegalArgumentException) {
                throw MalformedPairingUriException("Invalid bt_mac: ${e.message}", e)
            }

            // 5. Pairing Token
            val token = try {
                PairingToken.fromBase64Url(paramMap["pair_token"]!!)
            } catch (e: IllegalArgumentException) {
                throw MalformedPairingUriException("Invalid pair_token: ${e.message}", e)
            }

            // 6. Expiry
            val expStr = paramMap["exp"]!!
            val exp = expStr.toLongOrNull()
                ?: throw MalformedPairingUriException("Expiry must be an integer timestamp, got '$expStr'")

            if (currentEpochSec != null) {
                if (currentEpochSec > exp) {
                    throw MalformedPairingUriException("Pairing URI has expired ($currentEpochSec > $exp)")
                }
                if (exp - currentEpochSec > ProtocolConstants.MAX_QR_EXPIRY_WINDOW_SEC) {
                    throw MalformedPairingUriException("Pairing URI expiry exceeds the maximum allowed window")
                }
            }

            return PairingUri(
                version = version,
                pcId = pcId,
                pcName = decodedName,
                bluetoothMac = mac,
                pairingToken = token,
                expiryEpochSec = exp
            )
        }
    }
}

class MalformedPairingUriException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
