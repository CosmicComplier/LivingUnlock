#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <string_view>

namespace lockpin {
// Caller owns the returned HBITMAP and must DeleteObject it.
HBITMAP CreateQrBitmap(std::string_view payload, int width, int height);
}
