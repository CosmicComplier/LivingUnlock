#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include "session_store.h"
#include <limits>

namespace lockpin::phone {
bool BcryptRandomSource::Fill(std::uint8_t* output, std::size_t size) noexcept {
    if (!output || size == 0 || size > std::numeric_limits<ULONG>::max()) return false;
    return BCryptGenRandom(nullptr, output, static_cast<ULONG>(size), BCRYPT_USE_SYSTEM_PREFERRED_RNG) >= 0;
}

std::string SessionStore::UserMapKey(const UserKey& userKey) {
    return std::string(reinterpret_cast<const char*>(userKey.data()), userKey.size());
}

bool SessionStore::ConstantTimeEqual(const Nonce& left, const Nonce& right) noexcept {
    std::uint8_t difference = 0;
    for (std::size_t i = 0; i < left.size(); ++i) difference |= left[i] ^ right[i];
    return difference == 0;
}

void SessionStore::PruneLocked(std::uint64_t nowMs) {
    for (auto it = sessions_.begin(); it != sessions_.end();) {
        if (nowMs < it->second.challenge.expiresAtMs) { ++it; continue; }
        const auto user = UserMapKey(it->second.challenge.userKey);
        const auto active = activeByUser_.find(user);
        if (active != activeByUser_.end() && active->second == it->first) activeByUser_.erase(active);
        it = sessions_.erase(it);
    }
}

BeginResult SessionStore::Begin(const UserKey& userKey, std::uint64_t nowMs, std::uint64_t ttlMs) {
    if (ttlMs == 0 || ttlMs > 120000 || nowMs > std::numeric_limits<std::uint64_t>::max() - ttlMs)
        return {BeginStatus::InvalidTtl, {}};
    std::lock_guard<std::mutex> lock(mutex_);
    PruneLocked(nowMs);
    const auto user = UserMapKey(userKey);
    if (activeByUser_.find(user) != activeByUser_.end()) return {BeginStatus::ExistingActiveSession, {}};
    UnlockChallenge challenge;
    challenge.userKey = userKey;
    challenge.createdAtMs = nowMs;
    challenge.expiresAtMs = nowMs + ttlMs;
    std::array<std::uint8_t, 8> requestBytes{};
    if (!random_.Fill(requestBytes.data(), requestBytes.size()) ||
        !random_.Fill(challenge.nonce.data(), challenge.nonce.size())) return {BeginStatus::RandomFailure, {}};
    for (const auto value : requestBytes) challenge.requestId = (challenge.requestId << 8) | value;
    if (challenge.requestId == 0 || sessions_.find(challenge.requestId) != sessions_.end())
        return {BeginStatus::RandomFailure, {}};
    sessions_.emplace(challenge.requestId, Session{challenge, SessionState::Pending});
    activeByUser_.emplace(user, challenge.requestId);
    return {BeginStatus::Created, challenge};
}

UpdateStatus SessionStore::Approve(const RequestId& requestId, const Nonce& nonce, std::uint64_t nowMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = sessions_.find(requestId);
    if (it == sessions_.end()) return UpdateStatus::NotFound;
    if (nowMs >= it->second.challenge.expiresAtMs) return UpdateStatus::Expired;
    if (!ConstantTimeEqual(it->second.challenge.nonce, nonce)) return UpdateStatus::NonceMismatch;
    if (it->second.state == SessionState::Approved || it->second.state == SessionState::Consumed)
        return UpdateStatus::Replay;
    if (it->second.state == SessionState::Cancelled) return UpdateStatus::AlreadyFinalized;
    it->second.state = SessionState::Approved;
    return UpdateStatus::Updated;
}

UpdateStatus SessionStore::Consume(const RequestId& requestId, std::uint64_t nowMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = sessions_.find(requestId);
    if (it == sessions_.end()) return UpdateStatus::NotFound;
    if (nowMs >= it->second.challenge.expiresAtMs) return UpdateStatus::Expired;
    if (it->second.state == SessionState::Consumed) return UpdateStatus::Replay;
    if (it->second.state != SessionState::Approved) return UpdateStatus::AlreadyFinalized;
    it->second.state = SessionState::Consumed;
    const auto user = UserMapKey(it->second.challenge.userKey);
    const auto active = activeByUser_.find(user);
    if (active != activeByUser_.end() && active->second == it->first) activeByUser_.erase(active);
    return UpdateStatus::Updated;
}

UpdateStatus SessionStore::Cancel(const RequestId& requestId, std::uint64_t nowMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = sessions_.find(requestId);
    if (it == sessions_.end()) return UpdateStatus::NotFound;
    if (nowMs >= it->second.challenge.expiresAtMs) return UpdateStatus::Expired;
    if (it->second.state == SessionState::Consumed) return UpdateStatus::Replay;
    if (it->second.state == SessionState::Cancelled) return UpdateStatus::AlreadyFinalized;
    it->second.state = SessionState::Cancelled;
    const auto user = UserMapKey(it->second.challenge.userKey);
    const auto active = activeByUser_.find(user);
    if (active != activeByUser_.end() && active->second == it->first) activeByUser_.erase(active);
    return UpdateStatus::Updated;
}

void SessionStore::Prune(std::uint64_t nowMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    PruneLocked(nowMs);
}

std::size_t SessionStore::Size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sessions_.size();
}
}
