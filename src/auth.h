#pragma once
#include "platform.h"
#include <credentialprovider.h>

namespace lockpin {
inline constexpr GUID ProviderId = {0x16b44968, 0xdc91, 0x4f41, {0xbb, 0x1b, 0x30, 0xd3, 0x6b, 0x3f, 0x0b, 0xce}};
inline constexpr wchar_t ProviderIdString[] = L"{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}";
HRESULT PackIdentity(wchar_t* username, wchar_t* password,
    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* output) noexcept;
}
