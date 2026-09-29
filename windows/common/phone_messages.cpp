#include "phone_messages.h"
#include "broker_protocol.h"
#include <algorithm>
#include <cctype>
#include <limits>
#include <stdexcept>

namespace lockpin::phone {
namespace {
class Writer {
public:
    void U16(std::uint16_t value) { bytes_.push_back(static_cast<std::uint8_t>(value >> 8)); bytes_.push_back(static_cast<std::uint8_t>(value)); }
    void U32(std::uint32_t value) { for (int shift = 24; shift >= 0; shift -= 8) bytes_.push_back(static_cast<std::uint8_t>(value >> shift)); }
    void U64(std::uint64_t value) { for (int shift = 56; shift >= 0; shift -= 8) bytes_.push_back(static_cast<std::uint8_t>(value >> shift)); }
    void Bytes(const std::uint8_t* value, std::size_t size) {
        if (size > std::numeric_limits<std::uint16_t>::max()) throw std::length_error("Field too large");
        U16(static_cast<std::uint16_t>(size)); bytes_.insert(bytes_.end(), value, value + size);
    }
    void Text(const std::string& value) { Bytes(reinterpret_cast<const std::uint8_t*>(value.data()), value.size()); }
    std::vector<std::uint8_t> Take() { if (bytes_.size() > MaxPayloadSize) throw std::length_error("Payload too large"); return std::move(bytes_); }
private:
    std::vector<std::uint8_t> bytes_;
};

class Reader {
public:
    Reader(const std::uint8_t* data, std::size_t size) : data_(data), size_(size) {}
    bool U8(std::uint8_t& value) { if (!Need(1)) return false; value = data_[position_++]; return true; }
    bool U16(std::uint16_t& value) { if (!Need(2)) return false; value = static_cast<std::uint16_t>((data_[position_] << 8) | data_[position_ + 1]); position_ += 2; return true; }
    bool U32(std::uint32_t& value) { if (!Need(4)) return false; value = 0; for (int i = 0; i < 4; ++i) value = (value << 8) | data_[position_++]; return true; }
    bool U64(std::uint64_t& value) { if (!Need(8)) return false; value = 0; for (int i = 0; i < 8; ++i) value = (value << 8) | data_[position_++]; return true; }
    bool Bytes(std::vector<std::uint8_t>& value, std::size_t minimum, std::size_t maximum) {
        std::uint16_t length = 0; if (!U16(length) || length < minimum || length > maximum || !Need(length)) return false;
        value.assign(data_ + position_, data_ + position_ + length); position_ += length; return true;
    }
    bool Text(std::string& value, std::size_t minimum, std::size_t maximum) {
        std::vector<std::uint8_t> bytes; if (!Bytes(bytes, minimum, maximum) || !ValidUtf8(bytes)) return false;
        value.assign(bytes.begin(), bytes.end()); return true;
    }
    bool Done() const { return position_ == size_; }
private:
    bool Need(std::size_t count) const { return count <= size_ - position_; }
    static bool ValidUtf8(const std::vector<std::uint8_t>& bytes) {
        for (std::size_t i = 0; i < bytes.size();) {
            const auto first = bytes[i++];
            if (first <= 0x7f) { if (first < 0x20 && first != 0x09) return false; continue; }
            std::size_t continuation = 0;
            std::uint32_t codePoint = 0;
            if (first >= 0xc2 && first <= 0xdf) { continuation = 1; codePoint = first & 0x1f; }
            else if (first >= 0xe0 && first <= 0xef) { continuation = 2; codePoint = first & 0x0f; }
            else if (first >= 0xf0 && first <= 0xf4) { continuation = 3; codePoint = first & 0x07; }
            else return false;
            if (continuation > bytes.size() - i) return false;
            for (std::size_t j = 0; j < continuation; ++j) {
                const auto next = bytes[i++]; if ((next & 0xc0) != 0x80) return false;
                codePoint = (codePoint << 6) | (next & 0x3f);
            }
            if ((continuation == 2 && codePoint < 0x800) || (continuation == 3 && codePoint < 0x10000) ||
                (codePoint >= 0xd800 && codePoint <= 0xdfff) || codePoint > 0x10ffff) return false;
        }
        return true;
    }
    const std::uint8_t* data_ = nullptr;
    std::size_t size_ = 0;
    std::size_t position_ = 0;
};

bool ValidPcId(const std::string& value) {
    return value.size() >= 16 && value.size() <= 64 &&
        std::all_of(value.begin(), value.end(), [](unsigned char character) { return std::isxdigit(character) != 0; });
}
bool ValidDeviceId(const std::string& value) {
    return !value.empty() && value.size() <= 64 && std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return std::isalnum(character) || character == '.' || character == '_' || character == '-';
    });
}
bool ValidPublicKey(const std::vector<std::uint8_t>& value) {
    return value.size() == 33 || value.size() == 65;
}
bool ValidNonce(const std::vector<std::uint8_t>& value) { return value.size() >= 16 && value.size() <= 32; }
void ValidateChallenge(const UnlockChallengePayload& value) {
    if (!ValidPcId(value.pcId) || !ValidNonce(value.nonce) || value.ttlMs < 1000 || value.ttlMs > 300000 ||
        value.userDisplayName.empty() || value.userDisplayName.size() > 64) throw std::invalid_argument("Invalid unlock challenge");
}
void ValidateResponse(const UnlockResponsePayload& value) {
    if (value.statusCode > 3 || !ValidNonce(value.nonce)) throw std::invalid_argument("Invalid unlock response");
    if ((value.statusCode == 0 && (value.signature.size() < 68 || value.signature.size() > 72)) ||
        (value.statusCode != 0 && !value.signature.empty())) throw std::invalid_argument("Invalid unlock signature");
}
}

std::vector<std::uint8_t> EncodePairRequest(const PairRequestPayload& value) {
    if (!ValidDeviceId(value.deviceId) || value.deviceName.empty() || value.deviceName.size() > 64 ||
        value.pairingToken.size() < 16 || value.pairingToken.size() > 32 || !ValidPublicKey(value.clientPublicKey))
        throw std::invalid_argument("Invalid pairing request");
    Writer writer; writer.Text(value.deviceId); writer.Text(value.deviceName);
    writer.Bytes(value.pairingToken.data(), value.pairingToken.size());
    writer.Bytes(value.clientPublicKey.data(), value.clientPublicKey.size()); return writer.Take();
}
std::optional<PairRequestPayload> DecodePairRequest(const std::uint8_t* data, std::size_t size) noexcept {
    try { if (!data || size > MaxPayloadSize) return {}; Reader reader(data, size); PairRequestPayload value;
        if (!reader.Text(value.deviceId, 1, 64) || !ValidDeviceId(value.deviceId) ||
            !reader.Text(value.deviceName, 1, 64) || !reader.Bytes(value.pairingToken, 16, 32) ||
            !reader.Bytes(value.clientPublicKey, 33, 65) || !ValidPublicKey(value.clientPublicKey) || !reader.Done()) return {};
        return value; } catch (...) { return {}; }
}
std::vector<std::uint8_t> EncodePairResponse(const PairResponsePayload& value) {
    if ((value.statusCode == 0 && (!ValidPublicKey(value.serverPublicKey) || value.confirmationTag.size() != 32)) ||
        (value.statusCode != 0 && (!value.serverPublicKey.empty() || !value.confirmationTag.empty())))
        throw std::invalid_argument("Invalid pairing response");
    Writer writer; writer.U16(value.statusCode); writer.Bytes(value.serverPublicKey.data(), value.serverPublicKey.size());
    writer.Bytes(value.confirmationTag.data(), value.confirmationTag.size()); return writer.Take();
}
std::optional<PairResponsePayload> DecodePairResponse(const std::uint8_t* data, std::size_t size) noexcept {
    try { if (!data || size > MaxPayloadSize) return {}; Reader reader(data, size); PairResponsePayload value;
        if (!reader.U16(value.statusCode) || !reader.Bytes(value.serverPublicKey, 0, 65) ||
            !reader.Bytes(value.confirmationTag, 0, 32) || !reader.Done()) return {};
        if ((value.statusCode == 0 && (!ValidPublicKey(value.serverPublicKey) || value.confirmationTag.size() != 32)) ||
            (value.statusCode != 0 && (!value.serverPublicKey.empty() || !value.confirmationTag.empty()))) return {};
        return value; } catch (...) { return {}; }
}

std::vector<std::uint8_t> EncodeUnlockChallenge(const UnlockChallengePayload& value) {
    ValidateChallenge(value); Writer writer; writer.Text(value.pcId); writer.Bytes(value.nonce.data(), value.nonce.size());
    writer.U64(value.timestampMs); writer.U32(value.ttlMs); writer.Text(value.userDisplayName); return writer.Take();
}
std::optional<UnlockChallengePayload> DecodeUnlockChallenge(const std::uint8_t* data, std::size_t size) noexcept {
    try { if (!data || size > MaxPayloadSize) return {}; Reader reader(data, size); UnlockChallengePayload value;
        if (!reader.Text(value.pcId, 16, 64) || !ValidPcId(value.pcId) || !reader.Bytes(value.nonce, 16, 32) ||
            !reader.U64(value.timestampMs) || !reader.U32(value.ttlMs) || !reader.Text(value.userDisplayName, 1, 64) || !reader.Done()) return {};
        ValidateChallenge(value); return value; } catch (...) { return {}; }
}
std::vector<std::uint8_t> EncodeUnlockResponse(const UnlockResponsePayload& value) {
    ValidateResponse(value); Writer writer; writer.U16(value.statusCode); writer.Bytes(value.nonce.data(), value.nonce.size());
    writer.Bytes(value.signature.data(), value.signature.size()); return writer.Take();
}
std::optional<UnlockResponsePayload> DecodeUnlockResponse(const std::uint8_t* data, std::size_t size) noexcept {
    try { if (!data || size > MaxPayloadSize) return {}; Reader reader(data, size); UnlockResponsePayload value;
        if (!reader.U16(value.statusCode) || !reader.Bytes(value.nonce, 16, 32) || !reader.Bytes(value.signature, 0, 72) || !reader.Done()) return {};
        ValidateResponse(value); return value; } catch (...) { return {}; }
}
std::vector<std::uint8_t> EncodeUnlockResult(const UnlockResultPayload& value) {
    if (value.statusCode > 2 || value.message.size() > 128)
        throw std::invalid_argument("Invalid unlock result");
    Writer writer; writer.U16(value.statusCode); writer.Text(value.message); return writer.Take();
}
std::optional<UnlockResultPayload> DecodeUnlockResult(const std::uint8_t* data, std::size_t size) noexcept {
    try { if (!data || size > MaxPayloadSize) return {}; Reader reader(data, size); UnlockResultPayload value;
        if (!reader.U16(value.statusCode) || value.statusCode > 2 ||
            !reader.Text(value.message, 0, 128) || !reader.Done()) return {};
        return value;
    } catch (...) { return {}; }
}
std::vector<std::uint8_t> EncodeCancel(std::uint8_t reason) {
    if (reason < 1 || reason > 3) throw std::invalid_argument("Invalid cancellation reason"); return {reason};
}
std::optional<std::uint8_t> DecodeCancel(const std::uint8_t* data, std::size_t size) noexcept {
    if (!data || size != 1 || data[0] < 1 || data[0] > 3) return {}; return data[0];
}
std::vector<std::uint8_t> EncodeError(const ErrorPayload& value) {
    if (value.message.size() > 128) throw std::invalid_argument("Error message too long"); Writer writer;
    writer.U16(value.errorCode); writer.Text(value.message); return writer.Take();
}
std::optional<ErrorPayload> DecodeError(const std::uint8_t* data, std::size_t size) noexcept {
    try { if (!data || size > MaxPayloadSize) return {}; Reader reader(data, size); ErrorPayload value;
        if (!reader.U16(value.errorCode) || !reader.Text(value.message, 0, 128) || !reader.Done()) return {}; return value;
    } catch (...) { return {}; }
}
}
