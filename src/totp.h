#pragma once
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace lockpin {
using Secret = std::vector<unsigned char>;
// Pure algorithm helpers. Callers own secret lifetime and secure erasure.
std::string Hotp(const Secret& secret, std::uint64_t counter, unsigned digits = 6);
// Accept current step and at most one adjacent step in either direction.
// Caller MUST atomically persist the returned step and enforce rate limits.
std::optional<std::uint64_t> MatchTotp(const Secret& secret, std::string_view code,
    std::uint64_t unixSeconds, std::optional<std::uint64_t> lastAcceptedStep = {});
}
