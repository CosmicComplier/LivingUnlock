#pragma once
#include <array>
#include <cstdint>
#include <string_view>
#include <vector>

namespace lockpin::phone {
using Sha256Digest = std::array<std::uint8_t, 32>;

Sha256Digest Sha256(const std::vector<std::uint8_t>& data);
Sha256Digest HmacSha256(const std::vector<std::uint8_t>& key, const std::vector<std::uint8_t>& data);
std::vector<std::uint8_t> HkdfSha256(const std::vector<std::uint8_t>& salt,
    const std::vector<std::uint8_t>& inputKeyMaterial, std::string_view info, std::size_t outputSize);
Sha256Digest DerivePairingKey(std::string_view pcId, std::string_view deviceId,
    const std::vector<std::uint8_t>& pairingToken);
std::vector<std::uint8_t> BuildPairConfirmationTranscript(std::string_view pcId,
    std::string_view deviceId, const std::vector<std::uint8_t>& clientPublicKey,
    const std::vector<std::uint8_t>& serverPublicKey);
Sha256Digest ComputePairConfirmationTag(const Sha256Digest& pairingKey,
    const std::vector<std::uint8_t>& transcript);
bool ConstantTimeEqual(const Sha256Digest& left, const Sha256Digest& right) noexcept;
std::vector<std::uint8_t> GenerateP256PublicKey();
bool ValidateP256PublicKey(const std::vector<std::uint8_t>& sec1) noexcept;
bool VerifyP256Signature(const std::vector<std::uint8_t>& sec1PublicKey,
    const std::vector<std::uint8_t>& data,
    const std::vector<std::uint8_t>& derSignature) noexcept;
}
