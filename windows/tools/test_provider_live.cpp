#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <credentialprovider.h>
#include <cstdio>

#define PRINT(...) do { printf(__VA_ARGS__); fflush(stdout); } while(0)

typedef HRESULT (__stdcall *PFN_DllGetClassObject)(REFCLSID, REFIID, void**);
typedef HRESULT (__stdcall *PFN_DllCanUnloadNow)();

inline constexpr CLSID ProviderId = {0x16b44968, 0xdc91, 0x4f41, {0xbb, 0x1b, 0x30, 0xd3, 0x6b, 0x3f, 0x0b, 0xce}};

int main() {
    PRINT("[1] CoInitializeEx...\n");
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    PRINT("[2] LoadLibraryW...\n");
    HMODULE dll = LoadLibraryW(L"C:\\Program Files\\WindowsLockPin\\WindowsLockPin.dll");
    if (!dll) {
        PRINT("Failed to load DLL. Error: %lu\n", GetLastError());
        return 1;
    }
    PRINT("[2.1] Loaded DLL at %p\n", (void*)dll);

    auto getClassObject = reinterpret_cast<PFN_DllGetClassObject>(GetProcAddress(dll, "DllGetClassObject"));
    auto canUnload = reinterpret_cast<PFN_DllCanUnloadNow>(GetProcAddress(dll, "DllCanUnloadNow"));
    PRINT("[3] Exports: DllGetClassObject=%p, DllCanUnloadNow=%p\n", (void*)getClassObject, (void*)canUnload);
    if (!getClassObject || !canUnload) {
        PRINT("Missing exports!\n");
        return 1;
    }

    PRINT("[4] DllGetClassObject...\n");
    IClassFactory* factory = nullptr;
    HRESULT hr = getClassObject(ProviderId, IID_PPV_ARGS(&factory));
    PRINT("[4.1] DllGetClassObject hr = 0x%08lX, factory=%p\n", hr, (void*)factory);
    if (FAILED(hr) || !factory) {
        return 1;
    }

    PRINT("[5] CreateInstance...\n");
    ICredentialProvider* provider = nullptr;
    hr = factory->CreateInstance(nullptr, IID_PPV_ARGS(&provider));
    factory->Release();
    PRINT("[5.1] CreateInstance hr = 0x%08lX, provider=%p\n", hr, (void*)provider);
    if (FAILED(hr) || !provider) {
        return 1;
    }

    PRINT("[6] SetUsageScenario(CPUS_UNLOCK_WORKSTATION)...\n");
    hr = provider->SetUsageScenario(CPUS_UNLOCK_WORKSTATION, 0);
    PRINT("[6.1] SetUsageScenario hr = 0x%08lX\n", hr);

    PRINT("[7] GetFieldDescriptorCount...\n");
    DWORD fieldCount = 0;
    hr = provider->GetFieldDescriptorCount(&fieldCount);
    PRINT("[7.1] GetFieldDescriptorCount hr = 0x%08lX, count = %lu\n", hr, fieldCount);

    for (DWORD i = 0; i < fieldCount; ++i) {
        CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR* desc = nullptr;
        if (SUCCEEDED(provider->GetFieldDescriptorAt(i, &desc)) && desc) {
            PRINT("  Field %lu: %ls\n", i, desc->pszLabel ? desc->pszLabel : L"");
            CoTaskMemFree(desc->pszLabel);
            CoTaskMemFree(desc);
        }
    }

    PRINT("[8] Releasing provider...\n");
    provider->Release();
    PRINT("[8.1] Released provider\n");

    PRINT("[9] FreeLibrary...\n");
    FreeLibrary(dll);
    PRINT("[9.1] FreeLibrary done\n");

    CoUninitialize();
    PRINT("[10] Done successfully!\n");
    return 0;
}
