#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <cstdint>
#include <string>
#include <stdexcept>

namespace lockpin {
#define WinCheck(cond) do { if (!(cond)) throw std::runtime_error(std::string("WinCheck failed: " #cond " (line ") + std::to_string(__LINE__) + ")"); } while(0)
struct Handle {
    HANDLE value = INVALID_HANDLE_VALUE;
    explicit Handle(HANDLE h = INVALID_HANDLE_VALUE) : value(h) {}
    ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
};
template<class T> struct LocalBuffer {
    T* value = nullptr;
    ~LocalBuffer() { if (value) LocalFree(value); }
};
template<class T> void Wipe(T& value) { SecureZeroMemory(&value, sizeof(value)); }
std::wstring TokenSid(HANDLE token);
std::wstring CurrentSid();
std::uint64_t UnixNow();
}
