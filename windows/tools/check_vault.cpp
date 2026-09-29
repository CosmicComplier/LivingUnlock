#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <sddl.h>
#include "../../src/vault.h"
#include "../common/phone_vault.h"
#include <iostream>
#include <vector>

std::wstring GetCurrentUserSid() {
    HANDLE procToken = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &procToken)) return {};
    DWORD size = 0;
    GetTokenInformation(procToken, TokenUser, nullptr, 0, &size);
    std::vector<BYTE> buffer(size);
    if (size > 0 && GetTokenInformation(procToken, TokenUser, buffer.data(), size, &size)) {
        LPWSTR strSid = nullptr;
        if (ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &strSid)) {
            std::wstring res(strSid);
            LocalFree(strSid);
            CloseHandle(procToken);
            return res;
        }
    }
    CloseHandle(procToken);
    return {};
}

int main() {
    std::wstring sid = GetCurrentUserSid();
    std::wcout << L"Current user SID: " << sid << std::endl;

    bool phonePaired = lockpin::phone::IsPhonePaired(sid);
    std::wcout << L"Phone paired in vault: " << (phonePaired ? L"YES" : L"NO") << std::endl;
    if (phonePaired) {
        auto phone = lockpin::phone::LoadPairedPhone(sid);
        if (phone) {
            std::cout << "  Phone Device Name: " << phone->deviceName << "\n";
            std::cout << "  Phone Device ID: " << phone->deviceId << "\n";
            std::cout << "  PC ID: " << phone->pcId << "\n";
        }
    }

    bool enrolled = lockpin::IsEnrolled(sid);
    std::wcout << L"Enrolled in WindowsLockPin vault: " << (enrolled ? L"YES" : L"NO") << std::endl;

    lockpin::Record record;
    if (lockpin::LoadEnrolledCredentials(sid, record)) {
        std::wcout << L"SUCCESS: Credentials Loaded!" << std::endl;
        std::wcout << L"  Stored Username: " << record.username << std::endl;
        std::wcout << L"  Password Stored: " << (record.password[0] != 0 ? L"YES (Masked)" : L"NO") << std::endl;
    } else {
        std::wcout << L"FAILED: LoadEnrolledCredentials returned false" << std::endl;
    }

    return 0;
}
