#include "policy.h"
#include <iostream>
#include <stdexcept>
void Require(bool ok) { if (!ok) throw std::runtime_error("Policy test failed"); }
int main() {
    try {
        lockpin::Policy policy;
        for (int i = 0; i < 5; ++i) Require(lockpin::Admit(policy, 100) == lockpin::Admission::Allowed);
        Require(policy.failures == 5 && policy.blockedUntil == 160);
        Require(lockpin::Admit(policy, 101) == lockpin::Admission::Cooldown);
        Require(lockpin::Admit(policy, 159) == lockpin::Admission::Cooldown);
        Require(lockpin::Admit(policy, 158) == lockpin::Admission::ClockRollback);
        // A copy represents persisted/reloaded state, with no process-local counters.
        auto restarted = policy;
        Require(lockpin::Admit(restarted, 159) == lockpin::Admission::Cooldown);
        Require(lockpin::Admit(restarted, 160) == lockpin::Admission::Allowed);
        Require(restarted.failures == 1 && restarted.blockedUntil == 0);
        lockpin::Accept(restarted, 5);
        Require(restarted.lastStep == 5 && restarted.hasLastStep == 1 && restarted.failures == 0);
        Require(lockpin::Admit(restarted, 159) == lockpin::Admission::ClockRollback);
        Require(lockpin::Admit(restarted, 161) == lockpin::Admission::Allowed);
        std::cout << "PASS: five-attempt limit, cooldown, reloaded state, clock rollback, success reset\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
