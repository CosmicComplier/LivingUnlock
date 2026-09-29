#pragma once
#include <string>
#include <cstddef>

namespace lockpin {
inline std::wstring Base32(const unsigned char* bytes, std::size_t size) {
    constexpr wchar_t alphabet[] = L"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    std::wstring encoded;
    encoded.reserve((size * 8 + 4) / 5);
    unsigned accumulator = 0, bits = 0;
    for (std::size_t i = 0; i < size; ++i) {
        accumulator = (accumulator << 8) | bytes[i]; bits += 8;
        while (bits >= 5) { bits -= 5; encoded += alphabet[(accumulator >> bits) & 31]; }
    }
    if (bits) encoded += alphabet[(accumulator << (5 - bits)) & 31];
    return encoded;
}
}
