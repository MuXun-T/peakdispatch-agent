package com.peakdispatch.application.dispatch;

import com.peakdispatch.domain.dispatch.DispatchPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchPolicyService {
    private final DispatchPolicyProvider policies;
    private final Clock clock;

    public DispatchPolicyService(DispatchPolicyProvider policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    @Transactional
    public DispatchPolicyProvider.PolicySelection active(String regionId) {
        return policies.active(regionId);
    }

    @Transactional(readOnly = true)
    public DispatchPolicyProvider.PolicySelection find(UUID policyId) {
        return policies.find(policyId);
    }

    @Transactional(readOnly = true)
    public List<DispatchPolicyProvider.PolicySelection> list(String regionId) {
        return policies.list(regionId);
    }

    @Transactional(readOnly = true)
    public DispatchPolicyProvider.PolicySelection findByVersion(String regionId, long version) {
        return policies.findByVersion(regionId, version);
    }

    @Transactional
    public DispatchPolicyProvider.PolicySelection create(String regionId, DispatchPolicy policy, String actorId) {
        return policies.create(regionId, policy, actorId, Instant.now(clock));
    }

    @Transactional
    public DispatchPolicyProvider.PolicySelection updateDraft(UUID policyId, DispatchPolicy policy, String actorId) {
        return policies.updateDraft(policyId, policy, actorId, Instant.now(clock));
    }

    @Transactional
    public DispatchPolicyProvider.PolicySelection activate(UUID policyId, String actorId) {
        return policies.activate(policyId, actorId, Instant.now(clock));
    }

    @Transactional
    public DispatchPolicyProvider.PolicySelection deactivate(UUID policyId, String actorId) {
        return policies.deactivate(policyId, actorId, Instant.now(clock));
    }

    @Transactional
    public DispatchPolicyProvider.PolicySelection rollback(String regionId, long version, String actorId) {
        return policies.rollback(regionId, version, actorId, Instant.now(clock));
    }
}
