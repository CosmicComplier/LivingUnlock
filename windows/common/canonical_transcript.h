#pragma once
#include "broker_protocol.h"
#include <cstdint>
#include <string_view>
#include <vector>

namespace lockpin::phone {
std::vector<std::uint8_t> BuildUnlockTranscript(std::string_view pcId, std::string_view deviceId,
    RequestId requestId, const std::vector<std::uint8_t>& nonce, std::uint64_t timestampMs,
    std::uint32_t ttlMs);
}
