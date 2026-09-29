#include "canonical_transcript.h"
#include <limits>
#include <stdexcept>

namespace lockpin::phone {
namespace {
constexpr char Prefix[] = "WSLP-V1-UNLOCK-TRANSCRIPT";
void Write64(std::vector<std::uint8_t>& output, std::uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8) output.push_back(static_cast<std::uint8_t>(value >> shift));
}
void Write32(std::vector<std::uint8_t>& output, std::uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8) output.push_back(static_cast<std::uint8_t>(value >> shift));
}
void WriteBounded(std::vector<std::uint8_t>& output, std::string_view value, const char* label) {
    if (value.empty() || value.size() > std::numeric_limits<std::uint8_t>::max())
        throw std::invalid_argument(label);
    output.push_back(static_cast<std::uint8_t>(value.size()));
    output.insert(output.end(), value.begin(), value.end());
}
}
std::vector<std::uint8_t> BuildUnlockTranscript(std::string_view pcId, std::string_view deviceId,
    RequestId requestId, const std::vector<std::uint8_t>& nonce, std::uint64_t timestampMs,
    std::uint32_t ttlMs) {
    if (nonce.size() < 16 || nonce.size() > 32) throw std::invalid_argument("Invalid challenge nonce length");
    std::vector<std::uint8_t> output;
    output.reserve(sizeof(Prefix) + pcId.size() + deviceId.size() + nonce.size() + 23);
    output.insert(output.end(), Prefix, Prefix + sizeof(Prefix)); // Includes the required NUL.
    WriteBounded(output, pcId, "Invalid PC ID length");
    WriteBounded(output, deviceId, "Invalid device ID length");
    Write64(output, requestId);
    output.push_back(static_cast<std::uint8_t>(nonce.size()));
    output.insert(output.end(), nonce.begin(), nonce.end());
    Write64(output, timestampMs);
    Write32(output, ttlMs);
    return output;
}
}
