#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include "pairing_crypto.h"
#include <limits>
#include <stdexcept>

namespace lockpin::phone {
namespace {
struct Algorithm {
    BCRYPT_ALG_HANDLE value = nullptr;
    ~Algorithm() { if (value) BCryptCloseAlgorithmProvider(value, 0); }
};
struct Key {
    BCRYPT_KEY_HANDLE value = nullptr;
    ~Key() { if (value) BCryptDestroyKey(value); }
};
void Check(NTSTATUS status) { if (status < 0) throw std::runtime_error("Windows cryptography operation failed"); }
Sha256Digest Hash(bool hmac, const std::vector<std::uint8_t>& key, const std::vector<std::uint8_t>& data) {
    if (key.size() > std::numeric_limits<ULONG>::max() || data.size() > std::numeric_limits<ULONG>::max())
        throw std::length_error("Cryptographic input too large");
    Algorithm algorithm;
    Check(BCryptOpenAlgorithmProvider(&algorithm.value, BCRYPT_SHA256_ALGORITHM, nullptr,
        hmac ? BCRYPT_ALG_HANDLE_HMAC_FLAG : 0));
    Sha256Digest digest{};
    Check(BCryptHash(algorithm.value,
        hmac ? const_cast<PUCHAR>(key.data()) : nullptr, hmac ? static_cast<ULONG>(key.size()) : 0,
        const_cast<PUCHAR>(data.data()), static_cast<ULONG>(data.size()), digest.data(), static_cast<ULONG>(digest.size())));
    return digest;
}
void WriteLengthPrefixed(std::vector<std::uint8_t>& output, const std::uint8_t* data, std::size_t size) {
    if (!data || size > std::numeric_limits<std::uint16_t>::max()) throw std::invalid_argument("Invalid pairing field");
    output.push_back(static_cast<std::uint8_t>(size >> 8)); output.push_back(static_cast<std::uint8_t>(size));
    output.insert(output.end(), data, data + size);
}
void WriteText(std::vector<std::uint8_t>& output, std::string_view value) {
    WriteLengthPrefixed(output, reinterpret_cast<const std::uint8_t*>(value.data()), value.size());
}
void ValidateId(std::string_view value) {
    if (value.empty() || value.size() > 64) throw std::invalid_argument("Invalid pairing identifier");
}
void ValidatePublicKey(const std::vector<std::uint8_t>& key) {
    if (key.size() != 33 && key.size() != 65) throw std::invalid_argument("Invalid P-256 public key length");
}
}

Sha256Digest Sha256(const std::vector<std::uint8_t>& data) { return Hash(false, {}, data); }
Sha256Digest HmacSha256(const std::vector<std::uint8_t>& key, const std::vector<std::uint8_t>& data) {
    if (key.empty()) throw std::invalid_argument("HMAC key is empty");
    return Hash(true, key, data);
}
std::vector<std::uint8_t> HkdfSha256(const std::vector<std::uint8_t>& salt,
    const std::vector<std::uint8_t>& inputKeyMaterial, std::string_view info, std::size_t outputSize) {
    if (inputKeyMaterial.empty() || outputSize == 0 || outputSize > 255 * 32)
        throw std::invalid_argument("Invalid HKDF parameters");
    const std::vector<std::uint8_t> effectiveSalt = salt.empty() ? std::vector<std::uint8_t>(32, 0) : salt;
    const auto prkArray = HmacSha256(effectiveSalt, inputKeyMaterial);
    const std::vector<std::uint8_t> prk(prkArray.begin(), prkArray.end());
    std::vector<std::uint8_t> result; result.reserve(outputSize);
    std::vector<std::uint8_t> previous;
    for (std::uint16_t block = 1; result.size() < outputSize; ++block) {
        if (block > 255) throw std::length_error("HKDF output too large");
        std::vector<std::uint8_t> input(previous);
        input.insert(input.end(), info.begin(), info.end());
        input.push_back(static_cast<std::uint8_t>(block));
        const auto next = HmacSha256(prk, input);
        previous.assign(next.begin(), next.end());
        const auto count = (std::min)(previous.size(), outputSize - result.size());
        result.insert(result.end(), previous.begin(), previous.begin() + static_cast<std::ptrdiff_t>(count));
        SecureZeroMemory(input.data(), input.size());
    }
    SecureZeroMemory(previous.data(), previous.size());
    return result;
}
Sha256Digest DerivePairingKey(std::string_view pcId, std::string_view deviceId,
    const std::vector<std::uint8_t>& pairingToken) {
    ValidateId(pcId); ValidateId(deviceId);
    if (pairingToken.size() < 16 || pairingToken.size() > 32) throw std::invalid_argument("Invalid pairing token length");
    std::vector<std::uint8_t> saltInput;
    WriteText(saltInput, pcId); WriteText(saltInput, deviceId);
    const auto saltArray = Sha256(saltInput);
    const std::vector<std::uint8_t> salt(saltArray.begin(), saltArray.end());
    auto derived = HkdfSha256(salt, pairingToken, "WSLP-V1-PAIRING-KEY", 32);
    Sha256Digest result{};
    std::copy(derived.begin(), derived.end(), result.begin());
    SecureZeroMemory(derived.data(), derived.size()); SecureZeroMemory(saltInput.data(), saltInput.size());
    return result;
}
std::vector<std::uint8_t> BuildPairConfirmationTranscript(std::string_view pcId,
    std::string_view deviceId, const std::vector<std::uint8_t>& clientPublicKey,
    const std::vector<std::uint8_t>& serverPublicKey) {
    ValidateId(pcId); ValidateId(deviceId); ValidatePublicKey(clientPublicKey); ValidatePublicKey(serverPublicKey);
    constexpr char prefix[] = "WSLP-V1-PAIR-CONFIRM";
    std::vector<std::uint8_t> output(prefix, prefix + sizeof(prefix)); // Includes NUL.
    WriteText(output, pcId); WriteText(output, deviceId);
    WriteLengthPrefixed(output, clientPublicKey.data(), clientPublicKey.size());
    WriteLengthPrefixed(output, serverPublicKey.data(), serverPublicKey.size());
    return output;
}
Sha256Digest ComputePairConfirmationTag(const Sha256Digest& pairingKey,
    const std::vector<std::uint8_t>& transcript) {
    return HmacSha256(std::vector<std::uint8_t>(pairingKey.begin(), pairingKey.end()), transcript);
}
bool ConstantTimeEqual(const Sha256Digest& left, const Sha256Digest& right) noexcept {
    std::uint8_t difference = 0;
    for (std::size_t i = 0; i < left.size(); ++i) difference |= left[i] ^ right[i];
    return difference == 0;
}
std::vector<std::uint8_t> GenerateP256PublicKey() {
    Algorithm algorithm;
    Check(BCryptOpenAlgorithmProvider(&algorithm.value, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0));
    Key key;
    Check(BCryptGenerateKeyPair(algorithm.value, &key.value, 256, 0));
    Check(BCryptFinalizeKeyPair(key.value, 0));
    ULONG size = 0;
    Check(BCryptExportKey(key.value, nullptr, BCRYPT_ECCPUBLIC_BLOB, nullptr, 0, &size, 0));
    std::vector<std::uint8_t> blob(size);
    Check(BCryptExportKey(key.value, nullptr, BCRYPT_ECCPUBLIC_BLOB, blob.data(), size, &size, 0));
    if (size != sizeof(BCRYPT_ECCKEY_BLOB) + 64) throw std::runtime_error("Unexpected P-256 public key size");
    const auto* header = reinterpret_cast<const BCRYPT_ECCKEY_BLOB*>(blob.data());
    if (header->dwMagic != BCRYPT_ECDSA_PUBLIC_P256_MAGIC || header->cbKey != 32)
        throw std::runtime_error("Unexpected P-256 public key format");
    std::vector<std::uint8_t> sec1(65);
    sec1[0] = 0x04;
    std::copy(blob.begin() + sizeof(BCRYPT_ECCKEY_BLOB), blob.end(), sec1.begin() + 1);
    SecureZeroMemory(blob.data(), blob.size());
    return sec1;
}
bool ValidateP256PublicKey(const std::vector<std::uint8_t>& sec1) noexcept {
    try {
        if (sec1.size() != 65 || sec1[0] != 0x04) return false;
        Algorithm algorithm;
        Check(BCryptOpenAlgorithmProvider(&algorithm.value, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0));
        std::vector<std::uint8_t> blob(sizeof(BCRYPT_ECCKEY_BLOB) + 64);
        auto* header = reinterpret_cast<BCRYPT_ECCKEY_BLOB*>(blob.data());
        header->dwMagic = BCRYPT_ECDSA_PUBLIC_P256_MAGIC;
        header->cbKey = 32;
        std::copy(sec1.begin() + 1, sec1.end(), blob.begin() + sizeof(BCRYPT_ECCKEY_BLOB));
        Key key;
        const auto status = BCryptImportKeyPair(algorithm.value, nullptr, BCRYPT_ECCPUBLIC_BLOB,
            &key.value, blob.data(), static_cast<ULONG>(blob.size()), 0);
        SecureZeroMemory(blob.data(), blob.size());
        return status >= 0;
    } catch (...) { return false; }
}

namespace {
bool ParseDerInteger(const std::uint8_t*& ptr, const std::uint8_t* end, std::array<std::uint8_t, 32>& out) noexcept {
    if (ptr >= end || *ptr++ != 0x02) return false;
    if (ptr >= end) return false;
    std::size_t len = *ptr++;
    if (ptr + len > end || len == 0) return false;
    const std::uint8_t* val = ptr;
    ptr += len;
    if (len == 33 && val[0] == 0x00) {
        val++;
        len--;
    }
    if (len > 32) return false;
    out.fill(0);
    std::copy(val, val + len, out.end() - len);
    return true;
}

bool DerToRawP256Signature(const std::vector<std::uint8_t>& der, std::vector<std::uint8_t>& raw) noexcept {
    if (der.size() < 8 || der[0] != 0x30) return false;
    const std::uint8_t* ptr = der.data() + 1;
    const std::uint8_t* end = der.data() + der.size();
    std::size_t seqLen = *ptr++;
    if (seqLen & 0x80) {
        std::size_t numBytes = seqLen & 0x7f;
        if (numBytes != 1 || ptr >= end) return false;
        seqLen = *ptr++;
    }
    if (ptr + seqLen != end) return false;
    std::array<std::uint8_t, 32> r{}, s{};
    if (!ParseDerInteger(ptr, end, r) || !ParseDerInteger(ptr, end, s)) return false;
    if (ptr != end) return false;
    raw.resize(64);
    std::copy(r.begin(), r.end(), raw.begin());
    std::copy(s.begin(), s.end(), raw.begin() + 32);
    return true;
}
}

bool VerifyP256Signature(const std::vector<std::uint8_t>& sec1PublicKey,
    const std::vector<std::uint8_t>& data,
    const std::vector<std::uint8_t>& derSignature) noexcept {
    try {
        if (sec1PublicKey.size() != 65 || sec1PublicKey[0] != 0x04) return false;
        std::vector<std::uint8_t> rawSig;
        if (!DerToRawP256Signature(derSignature, rawSig)) return false;

        const auto hash = Sha256(data);

        Algorithm algorithm;
        Check(BCryptOpenAlgorithmProvider(&algorithm.value, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0));
        std::vector<std::uint8_t> blob(sizeof(BCRYPT_ECCKEY_BLOB) + 64);
        auto* header = reinterpret_cast<BCRYPT_ECCKEY_BLOB*>(blob.data());
        header->dwMagic = BCRYPT_ECDSA_PUBLIC_P256_MAGIC;
        header->cbKey = 32;
        std::copy(sec1PublicKey.begin() + 1, sec1PublicKey.end(), blob.begin() + sizeof(BCRYPT_ECCKEY_BLOB));

        Key key;
        const auto importStatus = BCryptImportKeyPair(algorithm.value, nullptr, BCRYPT_ECCPUBLIC_BLOB,
            &key.value, blob.data(), static_cast<ULONG>(blob.size()), 0);
        SecureZeroMemory(blob.data(), blob.size());
        if (importStatus < 0) return false;

        const auto verifyStatus = BCryptVerifySignature(key.value, nullptr,
            const_cast<PUCHAR>(hash.data()), static_cast<ULONG>(hash.size()),
            rawSig.data(), static_cast<ULONG>(rawSig.size()), 0);
        SecureZeroMemory(rawSig.data(), rawSig.size());
        return verifyStatus == 0;
    } catch (...) {
        return false;
    }
}
}

