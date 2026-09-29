#include "totp.h"
#include "base32.h"
#include <array>
#include <iostream>
#include <stdexcept>

void Require(bool ok) { if (!ok) throw std::runtime_error("Test failed"); }
int main() {
    try {
        // Public RFC 4226/6238 test key, never an enrolled user secret.
        const std::string testKey = "12345678901234567890";
        const lockpin::Secret key(testKey.begin(), testKey.end());
        const std::array<const char*, 7> raw = {"", "f", "fo", "foo", "foob", "fooba", "foobar"};
        const std::array<const wchar_t*, 7> base32 = {L"", L"MY", L"MZXQ", L"MZXW6", L"MZXW6YQ", L"MZXW6YTB", L"MZXW6YTBOI"};
        for (std::size_t i = 0; i < raw.size(); ++i) {
            const std::string value(raw[i]);
            Require(lockpin::Base32(reinterpret_cast<const unsigned char*>(value.data()), value.size()) == base32[i]);
        }
        Require(lockpin::Base32(key.data(), key.size()) == L"GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        const std::array<const char*, 10> hotp = {"755224", "287082", "359152", "969429",
            "338314", "254676", "287922", "162583", "399871", "520489"};
        for (std::size_t i = 0; i < hotp.size(); ++i) Require(lockpin::Hotp(key, i) == hotp[i]);
        const std::array<std::uint64_t, 6> times = {59, 1111111109, 1111111111,
            1234567890, 2000000000, 20000000000ULL};
        const std::array<const char*, 6> totp = {"94287082", "07081804", "14050471",
            "89005924", "69279037", "65353130"};
        for (std::size_t i = 0; i < times.size(); ++i)
            Require(lockpin::Hotp(key, times[i] / 30, 8) == totp[i]);
        Require(lockpin::MatchTotp(key, "287082", 59) == 1);
        Require(lockpin::MatchTotp(key, "287082", 60) == 1);
        Require(lockpin::MatchTotp(key, "359152", 59) == 2);
        Require(!lockpin::MatchTotp(key, "287082", 90));
        Require(!lockpin::MatchTotp(key, "287082", 59, 1));
        Require(!lockpin::MatchTotp(key, "287082", 59, 10));
        Require(lockpin::MatchTotp(key, "755224", 0) == 0);
        Require(!lockpin::MatchTotp(key, "28708", 59));
        Require(!lockpin::MatchTotp(key, "2870820", 59));
        Require(!lockpin::MatchTotp(key, "28708x", 59));
        Require(!lockpin::MatchTotp(key, " 87082", 59));
        Require(!lockpin::MatchTotp(key, "000000", 59));
        bool rejected = false;
        try { lockpin::Hotp({}, 0); } catch (const std::invalid_argument&) { rejected = true; }
        Require(rejected);
        std::cout << "PASS: RFC 4648 Base32, RFC 4226 / 6238 SHA-1 vectors, time window, replay cutoff, malformed input\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
