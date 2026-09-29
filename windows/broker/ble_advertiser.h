#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <bluetoothleapis.h>
#include <string>
#include <cstdint>

namespace lockpin::phone {

// BLE service UUID advertised while the PC is at the lock screen.
// Android's background scanner filters on this UUID and wakes the app.
// {A1B2C3D4-5678-9ABC-DEF0-123456789ABC}
inline constexpr GUID BleUnlockAdvertiseUuid = {
    0xa1b2c3d4, 0x5678, 0x9abc,
    {0xde, 0xf0, 0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc}
};

// Advertises a "PC is locked" BLE beacon.
// Uses the Windows BluetoothLE advertisement publisher API (no WinRT required).
// The advertisement contains a 16-byte service UUID so Android can filter it
// with zero battery impact using BluetoothLeScanner + PendingIntent scanning.
class BleAdvertiser {
public:
    BleAdvertiser() = default;
    ~BleAdvertiser() { Stop(); }
    BleAdvertiser(const BleAdvertiser&) = delete;
    BleAdvertiser& operator=(const BleAdvertiser&) = delete;

    // Start advertising. pcId is embedded in manufacturer data (first 8 bytes).
    bool Start(const std::string& pcId) noexcept;
    void Stop() noexcept;
    bool IsRunning() const noexcept { return handle_ != nullptr; }

private:
    HANDLE handle_ = nullptr;
};

} // namespace lockpin::phone
