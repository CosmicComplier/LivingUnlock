#include "vault.h"
#include "totp.h"
#include <sddl.h>
#include <aclapi.h>
#include <shlobj.h>
#include <wincrypt.h>
#include <array>
#include <vector>
#include <cwchar>
#include <iostream>

namespace lockpin {
#ifdef LOCKPIN_VAULT_TESTING
extern std::wstring TestVaultPath;
#endif
namespace {
constexpr wchar_t security[] = L"O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)";
struct Descriptor {
    PSECURITY_DESCRIPTOR value = nullptr;
    Descriptor() {
#ifdef LOCKPIN_VAULT_TESTING
        const auto sid = CurrentSid();
        const auto testSecurity = L"O:" + sid + L"D:P(A;OICI;FA;;;" + sid + L")";
        WinCheck(ConvertStringSecurityDescriptorToSecurityDescriptorW(
            testSecurity.c_str(), SDDL_REVISION_1, &value, nullptr));
#else
        WinCheck(ConvertStringSecurityDescriptorToSecurityDescriptorW(
            security, SDDL_REVISION_1, &value, nullptr));
#endif
    }
    ~Descriptor() { LocalFree(value); }
};
struct Plaintext {
    DATA_BLOB value{};
    ~Plaintext() {
        if (value.pbData) { SecureZeroMemory(value.pbData, value.cbData); LocalFree(value.pbData); }
    }
};
bool TrustedSid(PSID sid) {
    if (!sid) return false;
    if (IsWellKnownSid(sid, WinLocalSystemSid) || IsWellKnownSid(sid, WinBuiltinAdministratorsSid)) return true;
#ifdef LOCKPIN_VAULT_TESTING
    // Unit tests create an isolated vault owned by the test process. Production
    // vaults must remain accessible only to SYSTEM and BUILTIN\Administrators.
    try {
        LocalBuffer<void> current;
        if (ConvertStringSidToSidW(CurrentSid().c_str(), &current.value) && EqualSid(sid, current.value)) return true;
    } catch (...) {}
#endif
    return false;
}
void ValidateHandle(HANDLE file) {
    BY_HANDLE_FILE_INFORMATION info{};
    WinCheck(GetFileInformationByHandle(file, &info));
    WinCheck(!(info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT));
    PSID owner = nullptr;
    PACL acl = nullptr;
    PSECURITY_DESCRIPTOR raw = nullptr;
    const auto error = GetSecurityInfo(file, SE_FILE_OBJECT,
        OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION, &owner, nullptr, &acl, nullptr, &raw);
    LocalBuffer<void> descriptor;
    descriptor.value = raw;
    WinCheck(error == ERROR_SUCCESS && TrustedSid(owner) && acl);
    // Reject every ACE except ordinary grants to trusted principals.
    for (DWORD i = 0; i < acl->AceCount; ++i) {
        void* ace = nullptr;
        WinCheck(GetAce(acl, i, &ace));
        const auto header = static_cast<ACE_HEADER*>(ace);
        WinCheck(header->AceType == ACCESS_ALLOWED_ACE_TYPE);
        WinCheck(TrustedSid(&static_cast<ACCESS_ALLOWED_ACE*>(ace)->SidStart));
    }
}
void ValidateSid(const std::wstring& sid) {
    LocalBuffer<void> parsed;
    WinCheck(ConvertStringSidToSidW(sid.c_str(), &parsed.value));
    LocalBuffer<wchar_t> canonical;
    WinCheck(ConvertSidToStringSidW(parsed.value, &canonical.value));
    WinCheck(sid == canonical.value && sid.size() < 184);
}
struct Transaction {
    std::wstring directory;
    Handle directoryHandle;
    Handle lock;
    static HANDLE OpenDirectory(const std::wstring& path) {
        HANDLE h = CreateFileW(path.c_str(), READ_CONTROL | FILE_READ_ATTRIBUTES,
            FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
        WinCheck(h != INVALID_HANDLE_VALUE);
        return h;
    }
    static HANDLE OpenLock(const std::wstring& directory, const std::wstring& sid) {
        Descriptor descriptor;
        SECURITY_ATTRIBUTES attributes{sizeof(attributes), descriptor.value, FALSE};
        HANDLE h = CreateFileW((directory + L"\\" + sid + L".lock").c_str(),
            GENERIC_READ | GENERIC_WRITE | READ_CONTROL, 0, &attributes, OPEN_ALWAYS,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
        WinCheck(h != INVALID_HANDLE_VALUE);
        return h;
    }
    explicit Transaction(const std::wstring& sid) : directory(VaultDirectory()),
        directoryHandle(OpenDirectory(directory)) {
        ValidateSid(sid);
        ValidateHandle(directoryHandle.value);
        lock.value = OpenLock(directory, sid);
        ValidateHandle(lock.value);
    }
    std::wstring Path(const std::wstring& sid) const { return directory + L"\\" + sid + L".bin"; }
};
void Read(const std::wstring& path, Record& record) {
    Handle file(CreateFileW(path.c_str(), GENERIC_READ | READ_CONTROL, FILE_SHARE_READ,
        nullptr, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
    WinCheck(file.value != INVALID_HANDLE_VALUE);
    ValidateHandle(file.value);
    LARGE_INTEGER size{};
    WinCheck(GetFileSizeEx(file.value, &size) && size.QuadPart > 0 && size.QuadPart < 65536);
    std::vector<BYTE> encrypted(static_cast<std::size_t>(size.QuadPart));
    DWORD read = 0;
    WinCheck(ReadFile(file.value, encrypted.data(), static_cast<DWORD>(encrypted.size()), &read, nullptr));
    WinCheck(read == encrypted.size());
    DATA_BLOB input{read, encrypted.data()};
    Plaintext plain;
    WinCheck(CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr,
        CRYPTPROTECT_UI_FORBIDDEN, &plain.value));
    WinCheck(plain.value.cbData == sizeof(record));
    memcpy(&record, plain.value.pbData, sizeof(record));
    WinCheck(record.magic == 0x314b504c && record.version == 1);
    WinCheck(wmemchr(record.sid, 0, std::size(record.sid)) &&
        wmemchr(record.username, 0, std::size(record.username)) &&
        wmemchr(record.password, 0, std::size(record.password)));
    WinCheck(record.policy.hasLastStep <= 1 && record.policy.failures <= 5);
}
void Save(const std::wstring& path, const Record& record) {
    DATA_BLOB input{sizeof(record), reinterpret_cast<BYTE*>(const_cast<Record*>(&record))};
    Plaintext encrypted;
    WinCheck(CryptProtectData(&input, L"WindowsLockPin v1", nullptr, nullptr, nullptr,
        CRYPTPROTECT_LOCAL_MACHINE | CRYPTPROTECT_UI_FORBIDDEN, &encrypted.value));
    Descriptor descriptor;
    SECURITY_ATTRIBUTES attributes{sizeof(attributes), descriptor.value, FALSE};
    const auto temp = path + L".pending";
    // Remove only this transaction's stale encrypted staging file, never directories.
    if (!DeleteFileW(temp.c_str())) WinCheck(GetLastError() == ERROR_FILE_NOT_FOUND);
    {
        Handle file(CreateFileW(temp.c_str(), GENERIC_WRITE | READ_CONTROL, 0, &attributes,
            CREATE_NEW, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH, nullptr));
        WinCheck(file.value != INVALID_HANDLE_VALUE);
        ValidateHandle(file.value);
        DWORD written = 0;
        WinCheck(WriteFile(file.value, encrypted.value.pbData, encrypted.value.cbData, &written, nullptr));
        WinCheck(written == encrypted.value.cbData && FlushFileBuffers(file.value));
    }
    WinCheck(MoveFileExW(temp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH));
}
}

std::wstring TokenSid(HANDLE token) {
    DWORD size = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &size);
    WinCheck(size > 0);
    std::vector<BYTE> buffer(size);
    WinCheck(GetTokenInformation(token, TokenUser, buffer.data(), size, &size));
    LocalBuffer<wchar_t> sid;
    WinCheck(ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &sid.value));
    return sid.value;
}
std::wstring CurrentSid() {
    Handle token;
    WinCheck(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token.value));
    return TokenSid(token.value);
}
std::uint64_t UnixNow() {
    FILETIME time{};
    GetSystemTimeAsFileTime(&time);
    ULARGE_INTEGER value{};
    value.LowPart = time.dwLowDateTime;
    value.HighPart = time.dwHighDateTime;
    constexpr std::uint64_t epoch = 116444736000000000ULL;
    WinCheck(value.QuadPart >= epoch);
    return (value.QuadPart - epoch) / 10000000;
}
std::wstring VaultDirectory() {
#ifdef LOCKPIN_VAULT_TESTING
    WinCheck(!TestVaultPath.empty());
    return TestVaultPath;
#else
    PWSTR path = nullptr;
    WinCheck(SUCCEEDED(SHGetKnownFolderPath(FOLDERID_ProgramData, 0, nullptr, &path)));
    std::wstring result;
    try { result = std::wstring(path) + L"\\WindowsLockPin"; }
    catch (...) { CoTaskMemFree(path); throw; }
    CoTaskMemFree(path);
    return result;
#endif
}
void InitializeVault() {
    Descriptor descriptor;
    SECURITY_ATTRIBUTES attributes{sizeof(attributes), descriptor.value, FALSE};
    const auto directory = VaultDirectory();
    if (!CreateDirectoryW(directory.c_str(), &attributes)) WinCheck(GetLastError() == ERROR_ALREADY_EXISTS);
    Handle handle(Transaction::OpenDirectory(directory));
    ValidateHandle(handle.value);
}
bool IsEnrolled(const std::wstring& sid) noexcept {
    try {
        ValidateSid(sid);
        const auto directory = VaultDirectory();
        if (GetFileAttributesW((directory + L"\\" + sid + L".auth-disabled").c_str()) != INVALID_FILE_ATTRIBUTES)
            return false;
        try {
            Handle root(Transaction::OpenDirectory(directory));
            ValidateHandle(root.value);
            const auto path = directory + L"\\" + sid + L".bin";
            Handle file(CreateFileW(path.c_str(), FILE_READ_ATTRIBUTES | READ_CONTROL, FILE_SHARE_READ,
                nullptr, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
            if (file.value != INVALID_HANDLE_VALUE) {
                ValidateHandle(file.value);
                return true;
            }
        } catch (...) {}

        std::wstring regSubKey = L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList\\" + sid;
        HKEY hKey = nullptr;
        if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, regSubKey.c_str(), 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
            wchar_t profileDir[MAX_PATH]{};
            DWORD dataSize = sizeof(profileDir);
            DWORD type = 0;
            if (RegQueryValueExW(hKey, L"ProfileImagePath", nullptr, &type,
                reinterpret_cast<LPBYTE>(profileDir), &dataSize) == ERROR_SUCCESS) {
                wchar_t expandedDir[MAX_PATH]{};
                if (ExpandEnvironmentStringsW(profileDir, expandedDir, MAX_PATH) > 0) {
                    std::wstring fallbackPath = std::wstring(expandedDir) + L"\\AppData\\Local\\WindowsLockPin\\" + sid + L".bin";
                    DWORD attr = GetFileAttributesW(fallbackPath.c_str());
                    if (attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY)) {
                        RegCloseKey(hKey);
                        return true;
                    }
                }
            }
            RegCloseKey(hKey);
        }
        return false;
    } catch (...) { return false; }
}
void Enroll(const Record& record) {
    InitializeVault();
    Transaction transaction(record.sid);
    Save(transaction.Path(record.sid), record);
}
Verification Verify(const std::wstring& sid, std::string_view code, Record& output) {
    if (!IsEnrolled(sid)) return Verification::Invalid;
    Transaction transaction(sid);
    Record record;
    const auto path = transaction.Path(sid);
    Read(path, record);
    WinCheck(sid == record.sid);
    const auto admission = Admit(record.policy, UnixNow());
    if (admission == Admission::ClockRollback) return Verification::ClockRollback;
    Save(path, record); // Durable attempt counting, including process termination during verification.
    if (admission == Admission::Cooldown) return Verification::Cooldown;
    Secret key(std::begin(record.secret), std::end(record.secret));
    std::optional<std::uint64_t> match;
    try {
        match = MatchTotp(key, code, record.policy.lastSeen,
            record.policy.hasLastStep ? std::optional<std::uint64_t>(record.policy.lastStep) : std::nullopt);
    } catch (...) { SecureZeroMemory(key.data(), key.size()); throw; }
    SecureZeroMemory(key.data(), key.size());
    if (!match) return Verification::Invalid;
    Accept(record.policy, *match);
    Save(path, record); // Consume OTP before returning credentials. Any failure denies access.
    memcpy(&output, &record, sizeof(record));
    return Verification::Accepted;
}
bool LoadEnrolledCredentials(const std::wstring& sid, Record& output) {
    try {
        ValidateSid(sid);
        const auto directory = VaultDirectory();
        try {
            Handle root(Transaction::OpenDirectory(directory));
            ValidateHandle(root.value);
            // Bluetooth credentials are independent of the Authenticator enrollment.
            // Keep the legacy .bin fallback for installations created before .cred existed.
            const auto phonePath = directory + L"\\" + sid + L".cred";
            try {
                Record phoneRecord;
                Read(phonePath, phoneRecord);
                if (sid == phoneRecord.sid) {
                    memcpy(&output, &phoneRecord, sizeof(phoneRecord));
                    return true;
                }
            } catch (...) {}
            const auto path = directory + L"\\" + sid + L".bin";
            Record record;
            Read(path, record);
            if (sid == record.sid) {
                memcpy(&output, &record, sizeof(record));
                return true;
            }
        } catch (...) {}

        std::wstring regSubKey = L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList\\" + sid;
        HKEY hKey = nullptr;
        if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, regSubKey.c_str(), 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
            wchar_t profileDir[MAX_PATH]{};
            DWORD dataSize = sizeof(profileDir);
            DWORD type = 0;
            if (RegQueryValueExW(hKey, L"ProfileImagePath", nullptr, &type,
                reinterpret_cast<LPBYTE>(profileDir), &dataSize) == ERROR_SUCCESS) {
                wchar_t expandedDir[MAX_PATH]{};
                if (ExpandEnvironmentStringsW(profileDir, expandedDir, MAX_PATH) > 0) {
                    // The client stages phone credentials as <sid>.cred in the per-user
                    // vault; keep the legacy .bin fallback for older installations. A
                    // .cred record must never be enrolled as a TOTP secret (its secret
                    // is empty), so only the .bin candidate promotes into ProgramData.
                    struct FallbackCandidate { const wchar_t* extension; bool enroll; };
                    const FallbackCandidate candidates[] = {{L".cred", false}, {L".bin", true}};
                    for (const auto& candidate : candidates) {
                        std::wstring fallbackPath = std::wstring(expandedDir) + L"\\AppData\\Local\\WindowsLockPin\\" + sid + candidate.extension;
                        Handle file(CreateFileW(fallbackPath.c_str(), GENERIC_READ | READ_CONTROL, FILE_SHARE_READ,
                            nullptr, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
                        if (file.value == INVALID_HANDLE_VALUE) continue;
                        LARGE_INTEGER size{};
                        if (!GetFileSizeEx(file.value, &size) || size.QuadPart <= 0 || size.QuadPart >= 65536) continue;
                        std::vector<BYTE> encrypted(static_cast<std::size_t>(size.QuadPart));
                        DWORD read = 0;
                        if (!ReadFile(file.value, encrypted.data(), static_cast<DWORD>(encrypted.size()), &read, nullptr) ||
                            read != encrypted.size()) continue;
                        DATA_BLOB input{read, encrypted.data()};
                        Plaintext plain;
                        if (!CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr,
                            CRYPTPROTECT_UI_FORBIDDEN, &plain.value) ||
                            plain.value.cbData != sizeof(Record)) continue;
                        Record record;
                        memcpy(&record, plain.value.pbData, sizeof(record));
                        if (record.magic == 0x314b504c && record.version == 1 && sid == record.sid) {
                            memcpy(&output, &record, sizeof(record));
                            if (candidate.enroll) { try { Enroll(record); } catch (...) {} }
                            RegCloseKey(hKey);
                            return true;
                        }
                    }
                }
            }
            RegCloseKey(hKey);
        }
        return false;
    } catch (const std::exception& e) {
        std::cerr << "LoadEnrolledCredentials error: " << e.what() << " (WinError=" << GetLastError() << ")\n";
        return false;
    } catch (...) {
        std::cerr << "LoadEnrolledCredentials unknown error (WinError=" << GetLastError() << ")\n";
        return false;
    }
}
}
