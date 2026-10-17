package com.peakdispatch.application.dispatch;

import com.peakdispatch.domain.dispatch.DispatchPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Supplies an immutable policy selection for a dispatch decision. */
public interface DispatchPolicyProvider {
    PolicySelection active(String regionId);

    PolicySelection find(UUID policyId);

    default List<PolicySelection> list(String regionId) {
        throw new UnsupportedOperationException("policy listing is unavailable");
    }

    default PolicySelection findByVersion(String regionId, long version) {
        throw new UnsupportedOperationException("policy version lookup is unavailable");
    }

    default PolicySelection create(String regionId, DispatchPolicy policy, String actorId, Instant now) {
        throw new UnsupportedOperationException("policy persistence is unavailable");
    }

    default PolicySelection updateDraft(UUID policyId, DispatchPolicy policy, String actorId, Instant now) {
        throw new UnsupportedOperationException("policy draft updates are unavailable");
    }

    default PolicySelection activate(UUID policyId, String actorId, Instant now) {
        throw new UnsupportedOperationException("policy persistence is unavailable");
    }

    default PolicySelection deactivate(UUID policyId, String actorId, Instant now) {
        throw new UnsupportedOperationException("policy deactivation is unavailable");
    }

    default PolicySelection rollback(String regionId, long version, String actorId, Instant now) {
        throw new UnsupportedOperationException("policy persistence is unavailable");
    }

    record PolicySelection(UUID id, long version, String regionId, DispatchPolicy policy, String configJson,
                           String status) {
        public PolicySelection(UUID id, long version, String regionId, DispatchPolicy policy, String configJson) {
            this(id, version, regionId, policy, configJson, "ACTIVE");
        }

        public PolicySelection {
            if (policy == null || version <= 0 || regionId == null || regionId.isBlank()) {
                throw new IllegalArgumentException("invalid policy selection");
            }
            if (status == null || !status.matches("DRAFT|ACTIVE|RETIRED")) {
                throw new IllegalArgumentException("invalid policy status");
            }
        }
    }
}
