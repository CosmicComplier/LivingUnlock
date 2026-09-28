#include "broker_protocol.h"
#include <algorithm>
#include <stdexcept>

namespace lockpin::phone {
namespace {
constexpr std::array<std::uint8_t, 2> Magic{'W', 'L'};

void Write16(std::vector<std::uint8_t>& output, std::uint16_t value) {
    output.push_back(static_cast<std::uint8_t>(value >> 8));
    output.push_back(static_cast<std::uint8_t>(value));
}
void Write64(std::vector<std::uint8_t>& output, std::uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8)
        output.push_back(static_cast<std::uint8_t>(value >> shift));
}
std::uint16_t Read16(const std::uint8_t* value) {
    return static_cast<std::uint16_t>((static_cast<std::uint16_t>(value[0]) << 8) | value[1]);
}
std::uint64_t Read64(const std::uint8_t* value) {
    std::uint64_t result = 0;
    for (int i = 0; i < 8; ++i) result = (result << 8) | value[i];
    return result;
}
}

bool IsKnownMessageType(std::uint16_t raw) noexcept {
    return raw >= static_cast<std::uint16_t>(MessageType::PairRequest) &&
        raw <= static_cast<std::uint16_t>(MessageType::UnlockResult);
}

std::vector<std::uint8_t> EncodeFrame(const Frame& frame) {
    if (!IsKnownMessageType(static_cast<std::uint16_t>(frame.type)))
        throw std::invalid_argument("Unknown broker message type");
    if (frame.payload.size() > MaxPayloadSize)
        throw std::length_error("Broker payload exceeds the protocol limit");
    std::vector<std::uint8_t> output;
    output.reserve(FrameHeaderSize + frame.payload.size());
    output.insert(output.end(), Magic.begin(), Magic.end());
    output.push_back(ProtocolVersion);
    output.push_back(static_cast<std::uint8_t>(frame.type));
    Write16(output, 0); // Reserved flags must be zero in v1.
    Write64(output, frame.requestId);
    Write16(output, static_cast<std::uint16_t>(frame.payload.size()));
    output.insert(output.end(), frame.payload.begin(), frame.payload.end());
    return output;
}

DecodeResult DecodeFrame(const std::uint8_t* bytes, std::size_t size) noexcept {
    if (!bytes || size < FrameHeaderSize) return {{}, FrameDecodeError::TooShort};
    if (!std::equal(Magic.begin(), Magic.end(), bytes)) return {{}, FrameDecodeError::BadMagic};
    if (bytes[2] != ProtocolVersion) return {{}, FrameDecodeError::UnsupportedVersion};
    const auto rawType = bytes[3];
    if (!IsKnownMessageType(rawType)) return {{}, FrameDecodeError::UnknownMessageType};
    if (Read16(bytes + 4) != 0) return {{}, FrameDecodeError::ReservedFlagsNonZero};
    const auto payloadSize = Read16(bytes + 14);
    if (payloadSize > MaxPayloadSize) return {{}, FrameDecodeError::PayloadTooLarge};
    if (size != FrameHeaderSize + payloadSize) return {{}, FrameDecodeError::SizeMismatch};
    Frame frame;
    frame.type = static_cast<MessageType>(rawType);
    frame.requestId = Read64(bytes + 6);
    frame.payload.assign(bytes + FrameHeaderSize, bytes + size);
    return {std::move(frame), FrameDecodeError::None};
}
}
