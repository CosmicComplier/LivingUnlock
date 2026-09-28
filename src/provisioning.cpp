#include "provisioning.h"
#include <stdexcept>

namespace lockpin {
Provisioning BuildProvisioning(std::wstring_view base32Secret) {
    if (base32Secret.size() != 32) throw std::invalid_argument("Expected a 160-bit Base32 secret");
    std::string secret;
    secret.reserve(base32Secret.size());
    for (const wchar_t value : base32Secret) {
        if (!((value >= L'A' && value <= L'Z') || (value >= L'2' && value <= L'7')))
            throw std::invalid_argument("Invalid Base32 secret");
        secret.push_back(static_cast<char>(value));
    }
    const std::string suffix = secret.substr(secret.size() - 4);
    Provisioning result;
    result.accountLabel = L"Windows-" + std::wstring(base32Secret.substr(base32Secret.size() - 4));
    result.uri = "otpauth://totp/WindowsLockPin:Windows-" + suffix +
        "?secret=" + secret +
        "&issuer=WindowsLockPin&algorithm=SHA1&digits=6&period=30";
    return result;
}
}
