#include "auth.h"
#include <wincred.h>
#include <ntsecapi.h>

namespace lockpin {
HRESULT PackIdentity(wchar_t* username, wchar_t* password,
    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* output) noexcept {
    *output = {};
    HANDLE lsa = nullptr;
    auto status = LsaConnectUntrusted(&lsa);
    if (status < 0) return HRESULT_FROM_WIN32(LsaNtStatusToWinError(status));
    char name[] = "Negotiate";
    LSA_STRING packageName{static_cast<USHORT>(sizeof(name) - 1), sizeof(name), name};
    ULONG package = 0;
    status = LsaLookupAuthenticationPackage(lsa, &packageName, &package);
    LsaDeregisterLogonProcess(lsa);
    if (status < 0) return HRESULT_FROM_WIN32(LsaNtStatusToWinError(status));
    constexpr DWORD flags = CRED_PACK_PROTECTED_CREDENTIALS | CRED_PACK_ID_PROVIDER_CREDENTIALS;
    DWORD size = 0;
    if (CredPackAuthenticationBufferW(flags, username, password, nullptr, &size) ||
        GetLastError() != ERROR_INSUFFICIENT_BUFFER || !size) return E_FAIL;
    BYTE* packed = static_cast<BYTE*>(CoTaskMemAlloc(size));
    if (!packed) return E_OUTOFMEMORY;
    const DWORD allocated = size;
    if (!CredPackAuthenticationBufferW(flags, username, password, packed, &size)) {
        const auto error = GetLastError();
        SecureZeroMemory(packed, allocated);
        CoTaskMemFree(packed);
        return error ? HRESULT_FROM_WIN32(error) : E_FAIL;
    }
    output->clsidCredentialProvider = ProviderId;
    output->ulAuthenticationPackage = package;
    output->cbSerialization = size;
    output->rgbSerialization = packed;
    return S_OK;
}
}
