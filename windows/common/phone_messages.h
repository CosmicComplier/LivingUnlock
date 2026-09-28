#pragma once
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace lockpin::phone {
struct PairRequestPayload {
    std::string deviceId;
    std::string deviceName;
    std::vector<std::uint8_t> pairingToken;
    std::vector<std::uint8_t> clientPublicKey;
};
struct PairResponsePayload {
    std::uint16_t statusCode = 0;
    std::vector<std::uint8_t> serverPublicKey;
    std::vector<std::uint8_t> confirmationTag;
};
struct UnlockChallengePayload {
    std::string pcId;
    std::vector<std::uint8_t> nonce;
    std::uint64_t timestampMs = 0;
    std::uint32_t ttlMs = 0;
    std::string userDisplayName;
};
struct UnlockResponsePayload {
    std::uint16_t statusCode = 0;
    std::vector<std::uint8_t> nonce;
    std::vector<std::uint8_t> signature;
};
struct UnlockResultPayload {
    std::uint16_t statusCode = 0;
    std::string message;
};
struct ErrorPayload {
    std::uint16_t errorCode = 0;
    std::string message;
};

std::vector<std::uint8_t> EncodePairRequest(const PairRequestPayload& value);
std::optional<PairRequestPayload> DecodePairRequest(const std::uint8_t* data, std::size_t size) noexcept;
std::vector<std::uint8_t> EncodePairResponse(const PairResponsePayload& value);
std::optional<PairResponsePayload> DecodePairResponse(const std::uint8_t* data, std::size_t size) noexcept;
std::vector<std::uint8_t> EncodeUnlockChallenge(const UnlockChallengePayload& value);
std::optional<UnlockChallengePayload> DecodeUnlockChallenge(const std::uint8_t* data, std::size_t size) noexcept;
std::vector<std::uint8_t> EncodeUnlockResponse(const UnlockResponsePayload& value);
std::optional<UnlockResponsePayload> DecodeUnlockResponse(const std::uint8_t* data, std::size_t size) noexcept;
std::vector<std::uint8_t> EncodeUnlockResult(const UnlockResultPayload& value);
std::optional<UnlockResultPayload> DecodeUnlockResult(const std::uint8_t* data, std::size_t size) noexcept;
std::vector<std::uint8_t> EncodeCancel(std::uint8_t reason);
std::optional<std::uint8_t> DecodeCancel(const std::uint8_t* data, std::size_t size) noexcept;
std::vector<std::uint8_t> EncodeError(const ErrorPayload& value);
std::optional<ErrorPayload> DecodeError(const std::uint8_t* data, std::size_t size) noexcept;
}
