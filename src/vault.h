#pragma once
#include <cstdint>
#include "platform.h"
#include "policy.h"
#include <string_view>

namespace lockpin {
struct Record {
    std::uint32_t magic = 0x314b504c;
    std::uint32_t version = 1;
    wchar_t sid[184]{};
    wchar_t username[512]{};
    wchar_t password[1024]{};
    unsigned char secret[20]{};
    Policy policy{};
    Record() = default;
    Record(const Record&) = delete;
    Record& operator=(const Record&) = delete;
    ~Record() { Wipe(*this); }
};
// Machine DPAPI + protected SYSTEM/Administrators ACL. Administrators remain trusted.
std::wstring VaultDirectory();
void InitializeVault();
bool IsEnrolled(const std::wstring& sid) noexcept;
void Enroll(const Record& record); // Replaces only this SID's enrollment.
enum class Verification { Accepted, Invalid, Cooldown, ClockRollback };
Verification Verify(const std::wstring& sid, std::string_view code, Record& output);
bool LoadEnrolledCredentials(const std::wstring& sid, Record& output);
}
