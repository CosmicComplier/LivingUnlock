#pragma once
#include <string>
#include <string_view>

namespace lockpin {
struct Provisioning {
    std::string uri;
    std::wstring accountLabel;
};
Provisioning BuildProvisioning(std::wstring_view base32Secret);
}
