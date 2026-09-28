#include "provisioning.h"
#include "qr_bitmap.h"
#include "qrcodegen.hpp"
#include <iostream>
#include <stdexcept>
#include <string>

void Require(bool ok) { if (!ok) throw std::runtime_error("QR test failed"); }
int main() {
    try {
        const auto data = lockpin::BuildProvisioning(L"GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        Require(data.accountLabel == L"Windows-QOJQ");
        Require(data.uri == "otpauth://totp/WindowsLockPin:Windows-QOJQ?secret="
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=WindowsLockPin&algorithm=SHA1&digits=6&period=30");
        const auto qr = qrcodegen::QrCode::encodeText(data.uri.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
        Require(qr.getSize() >= 21 && qr.getModule(0, 0) && qr.getModule(6, 0) && qr.getModule(0, 6));
        HBITMAP bitmap = lockpin::CreateQrBitmap(data.uri, 256, 256);
        Require(bitmap != nullptr);
        DIBSECTION section{};
        Require(GetObjectW(bitmap, sizeof(section), &section) == sizeof(section));
        Require(section.dsBm.bmWidth == 256 && section.dsBm.bmHeight == 256 && section.dsBm.bmBitsPixel == 32);
        auto* pixels = static_cast<unsigned long*>(section.dsBm.bmBits);
        bool black = false, white = false;
        for (int i = 0; i < 256 * 256; ++i) {
            black = black || pixels[i] == 0x00000000u;
            white = white || pixels[i] == 0x00ffffffu;
        }
        Require(black && white);
        DeleteObject(bitmap);
        bool rejected = false;
        try { lockpin::BuildProvisioning(L"NOT-A-VALID-SECRET"); }
        catch (const std::invalid_argument&) { rejected = true; }
        Require(rejected);
        std::cout << "PASS: otpauth URI, QR matrix, quiet-zone bitmap, invalid secret rejection\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
