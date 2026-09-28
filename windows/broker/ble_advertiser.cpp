// ble_advertiser.cpp — Isolated WinRT compilation unit for BLE advertising.
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Storage.Streams.h>

#include "ble_advertiser.h"
#include <cstring>
#include <memory>

namespace lockpin::phone {

namespace {
using namespace winrt::Windows::Devices::Bluetooth::Advertisement;
using namespace winrt::Windows::Storage::Streams;

// Store the publisher as a raw allocation using winrt's ref-counting via a heap-allocated wrapper
struct PublisherHolder {
    BluetoothLEAdvertisementPublisher publisher;
};

winrt::guid GuidToWinrt(const ::GUID& g) noexcept {
    winrt::guid out{};
    std::memcpy(&out, &g, sizeof(g));
    return out;
}
} // namespace

bool BleAdvertiser::Start(const std::string& pcId) noexcept {
    try {
        Stop();

        winrt::init_apartment(winrt::apartment_type::multi_threaded);

        auto holder = std::make_unique<PublisherHolder>();
        auto& pub = holder->publisher;

        // Service UUID filter — Android scans for this UUID
        auto adv = pub.Advertisement();
        adv.ServiceUuids().Append(GuidToWinrt(BleUnlockAdvertiseUuid));

        // Manufacturer data: company 0xFFFF (test/reserved) + up to 8 bytes of pcId
        BluetoothLEManufacturerData mfr;
        mfr.CompanyId(0xFFFF);
        DataWriter writer;
        const auto len = std::min<std::size_t>(pcId.size(), 8u);
        for (std::size_t i = 0; i < len; ++i)
            writer.WriteByte(static_cast<uint8_t>(pcId[i]));
        mfr.Data(writer.DetachBuffer());
        adv.ManufacturerData().Append(mfr);

        pub.Start();

        handle_ = holder.release();   // transfer ownership to raw pointer
        return true;
    } catch (...) {
        handle_ = nullptr;
        return false;
    }
}

void BleAdvertiser::Stop() noexcept {
    if (handle_) {
        try {
            auto* h = static_cast<PublisherHolder*>(handle_);
            h->publisher.Stop();
            delete h;
        } catch (...) {}
        handle_ = nullptr;
    }
}

} // namespace lockpin::phone
