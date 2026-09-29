#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include "totp.h"
#include <array>
#include <limits>
#include <stdexcept>

namespace lockpin {
namespace {
struct Algorithm {
    BCRYPT_ALG_HANDLE handle = nullptr;
    ~Algorithm() { if (handle) BCryptCloseAlgorithmProvider(handle, 0); }
};
void Check(NTSTATUS status) {
    if (status < 0) throw std::runtime_error("Windows cryptography operation failed");
}
}
std::string Hotp(const Secret& secret, std::uint64_t counter, unsigned digits) {
    if (secret.empty() || secret.size() > std::numeric_limits<ULONG>::max() ||
        (digits != 6 && digits != 8)) throw std::invalid_argument("Invalid HOTP parameters");
    Algorithm algorithm;
    Check(BCryptOpenAlgorithmProvider(&algorithm.handle, BCRYPT_SHA1_ALGORITHM,
        nullptr, BCRYPT_ALG_HANDLE_HMAC_FLAG));
    std::array<UCHAR, 8> message{};
    for (unsigned i = 0; i < 8; ++i) {
        message[7 - i] = static_cast<UCHAR>(counter & 0xff);
        counter >>= 8;
    }
    std::array<UCHAR, 20> digest{};
    const auto status = BCryptHash(algorithm.handle,
        const_cast<PUCHAR>(secret.data()), static_cast<ULONG>(secret.size()),
        message.data(), static_cast<ULONG>(message.size()),
        digest.data(), static_cast<ULONG>(digest.size()));
    if (status < 0) {
        SecureZeroMemory(digest.data(), digest.size());
        Check(status);
    }
    const auto offset = digest.back() & 0x0f;
    std::uint32_t value = (static_cast<std::uint32_t>(digest[offset] & 0x7f) << 24)
        | (static_cast<std::uint32_t>(digest[offset + 1]) << 16)
        | (static_cast<std::uint32_t>(digest[offset + 2]) << 8)
        | digest[offset + 3];
    SecureZeroMemory(digest.data(), digest.size());
    value %= digits == 6 ? 1000000u : 100000000u;
    std::string result(digits, '0');
    for (unsigned i = 0; i < digits; ++i) {
        result[digits - i - 1] = static_cast<char>('0' + value % 10);
        value /= 10;
    }
    return result;
}

std::optional<std::uint64_t> MatchTotp(const Secret& secret, std::string_view code,
    std::uint64_t unixSeconds, std::optional<std::uint64_t> lastAcceptedStep) {
    if (code.size() != 6) return {};
    for (char c : code) if (c < '0' || c > '9') return {};
    const std::uint64_t current = unixSeconds / 30;
    std::optional<std::uint64_t> matched;
    // Search all candidates; do not return early based on matching digits.
    for (int delta = -1; delta <= 1; ++delta) {
        if (delta < 0 && current == 0) continue;
        const auto candidate = delta < 0 ? current - 1 : current + static_cast<unsigned>(delta);
        auto expected = Hotp(secret, candidate);
        unsigned difference = 0;
        for (std::size_t i = 0; i < code.size(); ++i)
            difference |= static_cast<unsigned>(expected[i] ^ code[i]);
        SecureZeroMemory(expected.data(), expected.size());
        if (difference == 0 && (!lastAcceptedStep || candidate > *lastAcceptedStep))
            matched = candidate;
    }
    return matched;
}
}
