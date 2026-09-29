#pragma once
#include <cstdint>

namespace lockpin {
struct Policy {
    std::uint64_t lastSeen = 0;
    std::uint64_t blockedUntil = 0;
    std::uint64_t lastStep = 0;
    std::uint32_t failures = 0;
    std::uint32_t hasLastStep = 0;
};
enum class Admission { Allowed, Cooldown, ClockRollback };
inline Admission Admit(Policy& state, std::uint64_t now) {
    if (now < state.lastSeen) return Admission::ClockRollback;
    state.lastSeen = now;
    if (now < state.blockedUntil) return Admission::Cooldown;
    if (state.blockedUntil) { state.failures = 0; state.blockedUntil = 0; }
    // Persist this pessimistic attempt BEFORE evaluating the secret.
    ++state.failures;
    if (state.failures >= 5) state.blockedUntil = now + 60;
    return Admission::Allowed;
}
inline void Accept(Policy& state, std::uint64_t step) {
    state.hasLastStep = 1;
    state.lastStep = step;
    state.failures = 0;
    state.blockedUntil = 0;
}
}
