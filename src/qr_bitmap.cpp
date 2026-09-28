#include "qr_bitmap.h"
#include "platform.h"
#include "qrcodegen.hpp"
#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>

namespace lockpin {
HBITMAP CreateQrBitmap(std::string_view payload, int width, int height) {
    if (payload.empty() || width <= 0 || height <= 0) throw std::invalid_argument("Invalid QR input");
    std::string text(payload);
    qrcodegen::QrCode code = qrcodegen::QrCode::encodeText(text.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
    SecureZeroMemory(text.data(), text.size());
    constexpr int quietZone = 4;
    const int modules = code.getSize() + quietZone * 2;
    const int scale = (std::min)(width, height) / modules;
    if (scale < 2) throw std::runtime_error("QR control is too small");
    const int drawn = modules * scale;
    const int startX = (width - drawn) / 2;
    const int startY = (height - drawn) / 2;

    BITMAPINFO info{};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void* raw = nullptr;
    HBITMAP bitmap = CreateDIBSection(nullptr, &info, DIB_RGB_COLORS, &raw, nullptr, 0);
    if (!bitmap || !raw) {
        if (bitmap) DeleteObject(bitmap);
        throw std::runtime_error("Unable to allocate QR bitmap");
    }
    auto* pixels = static_cast<std::uint32_t*>(raw);
    std::fill(pixels, pixels + static_cast<std::size_t>(width) * height, 0x00ffffffu);
    for (int moduleY = 0; moduleY < code.getSize(); ++moduleY) {
        for (int moduleX = 0; moduleX < code.getSize(); ++moduleX) {
            if (!code.getModule(moduleX, moduleY)) continue;
            const int left = startX + (moduleX + quietZone) * scale;
            const int top = startY + (moduleY + quietZone) * scale;
            for (int y = top; y < top + scale; ++y)
                std::fill(pixels + static_cast<std::size_t>(y) * width + left,
                    pixels + static_cast<std::size_t>(y) * width + left + scale, 0x00000000u);
        }
    }
    return bitmap;
}
}
