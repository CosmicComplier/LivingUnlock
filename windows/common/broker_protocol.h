#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace lockpin::phone {
inline constexpr std::size_t FrameHeaderSize = 16;
inline constexpr std::size_t MaxFrameSize = 8192;
inline constexpr std::size_t MaxPayloadSize = MaxFrameSize - FrameHeaderSize;
inline constexpr std::uint8_t ProtocolVersion = 1;

using RequestId = std::uint64_t;

enum class MessageType : std::uint16_t {
    PairRequest = 1,
    PairResponse = 2,
    UnlockChallenge = 3,
    UnlockResponse = 4,
    Cancel = 5,
    Error = 6,
    Ping = 7,
    Pong = 8,
    UnlockResult = 9,
};

struct Frame {
    MessageType type = MessageType::Error;
    RequestId requestId = 0;
    std::vector<std::uint8_t> payload;
};

enum class FrameDecodeError {
    None,
    TooShort,
    BadMagic,
    UnsupportedVersion,
    UnknownMessageType,
    ReservedFlagsNonZero,
    PayloadTooLarge,
    SizeMismatch,
};

struct DecodeResult {
    std::optional<Frame> frame;
    FrameDecodeError status = FrameDecodeError::None;
};

bool IsKnownMessageType(std::uint16_t raw) noexcept;
std::vector<std::uint8_t> EncodeFrame(const Frame& frame);
DecodeResult DecodeFrame(const std::uint8_t* bytes, std::size_t size) noexcept;
}
