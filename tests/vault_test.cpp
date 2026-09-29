#include "vault.h"
#include "totp.h"
#include <iostream>
#include <thread>
#include <atomic>
#include <vector>

namespace lockpin { std::wstring TestVaultPath; }
void Require(bool ok) { if (!ok) throw std::runtime_error("Vault integration test failed"); }
int main() {
    try {
        wchar_t executable[MAX_PATH]{};
        Require(GetModuleFileNameW(nullptr, executable, MAX_PATH) > 0);
        std::wstring directory(executable);
        directory.resize(directory.find_last_of(L'\\'));
        lockpin::TestVaultPath = directory + L"\\vault-test-" + std::to_wstring(GetCurrentProcessId());
        Require(GetFileAttributesW(lockpin::TestVaultPath.c_str()) == INVALID_FILE_ATTRIBUTES);
        lockpin::Record input;
        wcscpy_s(input.sid, L"S-1-5-21-1-2-3-1001");
        wcscpy_s(input.username, L"MicrosoftAccount\\test@example.invalid");
        wcscpy_s(input.password, L"PUBLIC-NONCREDENTIAL-TEST-DATA");
        const std::string publicKey = "12345678901234567890";
        memcpy(input.secret, publicKey.data(), sizeof(input.secret));
        const lockpin::Secret key(publicKey.begin(), publicKey.end());
        const auto code = lockpin::Hotp(key, lockpin::UnixNow() / 30);
        lockpin::Enroll(input);
        Require(lockpin::IsEnrolled(input.sid));
        const auto disabledPath = lockpin::TestVaultPath + L"\\" + input.sid + L".auth-disabled";
        {
            lockpin::Handle marker(CreateFileW(disabledPath.c_str(), GENERIC_WRITE, 0,
                nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr));
            Require(marker.value != INVALID_HANDLE_VALUE);
        }
        Require(!lockpin::IsEnrolled(input.sid));
        lockpin::Record disabledOutput;
        Require(lockpin::Verify(input.sid, code, disabledOutput) == lockpin::Verification::Invalid);
        Require(DeleteFileW(disabledPath.c_str()));
        Require(lockpin::IsEnrolled(input.sid));
        const auto credentialsPath = lockpin::TestVaultPath + L"\\" + input.sid + L".cred";
        Require(CopyFileW((lockpin::TestVaultPath + L"\\" + input.sid + L".bin").c_str(),
            credentialsPath.c_str(), FALSE));
        lockpin::Record phoneCredentials;
        Require(lockpin::LoadEnrolledCredentials(input.sid, phoneCredentials));
        Require(wcscmp(phoneCredentials.password, input.password) == 0);
        Require(DeleteFileW(credentialsPath.c_str()));
        lockpin::Record output;
        Require(lockpin::Verify(input.sid, code, output) == lockpin::Verification::Accepted);
        Require(wcscmp(input.password, output.password) == 0);
        // Every Verify opens, decrypts and reads state afresh from disk.
        for (int i = 0; i < 5; ++i)
            Require(lockpin::Verify(input.sid, code, output) == lockpin::Verification::Invalid);
        Require(lockpin::Verify(input.sid, code, output) == lockpin::Verification::Cooldown);
        input.policy.lastSeen = lockpin::UnixNow() + 3600;
        lockpin::Enroll(input);
        Require(lockpin::Verify(input.sid, code, output) == lockpin::Verification::ClockRollback);
        input.policy = {};
        lockpin::Enroll(input);
        const auto concurrentCode = lockpin::Hotp(key, lockpin::UnixNow() / 30);
        std::atomic<int> accepted{0};
        std::atomic<bool> start{false};
        auto attempt = [&] {
            while (!start.load()) std::this_thread::yield();
            try {
                lockpin::Record result;
                if (lockpin::Verify(input.sid, concurrentCode, result) == lockpin::Verification::Accepted) ++accepted;
            } catch (...) { /* Exclusive lock contention is a fail-closed result. */ }
        };
        std::thread first(attempt), second(attempt);
        start = true;
        first.join(); second.join();
        Require(accepted == 1);
        const auto path = lockpin::TestVaultPath + L"\\" + input.sid + L".bin";
        // Corrupt an encrypted synthetic record; the next read must fail closed.
        {
            lockpin::Handle file(CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE, 0,
                nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
            Require(file.value != INVALID_HANDLE_VALUE);
            const auto size = GetFileSize(file.value, nullptr);
            Require(size > 100 && size < 65536);
            std::vector<unsigned char> bytes(size);
            DWORD count = 0;
            Require(ReadFile(file.value, bytes.data(), size, &count, nullptr) && count == size);
            bytes[size / 2] ^= 0x80;
            SetFilePointer(file.value, 0, nullptr, FILE_BEGIN);
            Require(WriteFile(file.value, bytes.data(), size, &count, nullptr) && count == size);
        }
        bool rejected = false;
        try { lockpin::Verify(input.sid, code, output); } catch (...) { rejected = true; }
        Require(rejected);
        // Exact known paths only; no recursive cleanup or user data access.
        Require(DeleteFileW(path.c_str()));
        Require(DeleteFileW((lockpin::TestVaultPath + L"\\" + input.sid + L".lock").c_str()));
        Require(RemoveDirectoryW(lockpin::TestVaultPath.c_str()));
        std::cout << "PASS: synthetic DPAPI storage, persisted replay/cooldown, clock rollback, concurrent OTP, corruption rejection\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
