#pragma once
#include "../common/broker_protocol.h"
#include <array>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>

namespace lockpin::phone {
inline constexpr std::size_t UserKeySize = 32;
inline constexpr std::size_t NonceSize = 32;
using UserKey = std::array<std::uint8_t, UserKeySize>;
using Nonce = std::array<std::uint8_t, NonceSize>;

class RandomSource {
public:
    virtual ~RandomSource() = default;
    virtual bool Fill(std::uint8_t* output, std::size_t size) noexcept = 0;
};

class BcryptRandomSource final : public RandomSource {
public:
    bool Fill(std::uint8_t* output, std::size_t size) noexcept override;
};

enum class SessionState { Pending, Approved, Cancelled, Consumed };
struct UnlockChallenge {
    RequestId requestId{};
    UserKey userKey{};
    Nonce nonce{};
    std::uint64_t createdAtMs = 0;
    std::uint64_t expiresAtMs = 0;
};
enum class BeginStatus { Created, ExistingActiveSession, RandomFailure, InvalidTtl };
struct BeginResult {
    BeginStatus status = BeginStatus::RandomFailure;
    std::optional<UnlockChallenge> challenge;
};
enum class UpdateStatus { Updated, NotFound, Expired, NonceMismatch, Replay, AlreadyFinalized };

class SessionStore {
public:
    explicit SessionStore(RandomSource& random) : random_(random) {}
    BeginResult Begin(const UserKey& userKey, std::uint64_t nowMs, std::uint64_t ttlMs = 30000);
    UpdateStatus Approve(const RequestId& requestId, const Nonce& nonce, std::uint64_t nowMs);
    UpdateStatus Consume(const RequestId& requestId, std::uint64_t nowMs);
    UpdateStatus Cancel(const RequestId& requestId, std::uint64_t nowMs);
    void Prune(std::uint64_t nowMs);
    std::size_t Size() const;

private:
    struct Session {
        UnlockChallenge challenge;
        SessionState state = SessionState::Pending;
    };
    static std::string UserMapKey(const UserKey& userKey);
    static bool ConstantTimeEqual(const Nonce& left, const Nonce& right) noexcept;
    void PruneLocked(std::uint64_t nowMs);

    RandomSource& random_;
    mutable std::mutex mutex_;
    std::unordered_map<RequestId, Session> sessions_;
    std::unordered_map<std::string, RequestId> activeByUser_;
};
}
