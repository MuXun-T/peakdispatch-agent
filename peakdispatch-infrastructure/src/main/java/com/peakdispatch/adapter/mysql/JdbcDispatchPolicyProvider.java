package com.peakdispatch.adapter.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.dispatch.DispatchPolicyProvider;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import com.peakdispatch.domain.dispatch.DispatchPolicy;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDispatchPolicyProvider implements DispatchPolicyProvider {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDispatchPolicyProvider(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PolicySelection active(String regionId) {
        requireRegion(regionId);
        lockRegion(regionId, Instant.now());
        List<PolicySelection> active = jdbc.query("""
                SELECT p.id, p.version, p.region_id, p.config_json, p.status
                FROM dispatch_policy_region r JOIN dispatch_policy p ON p.id = r.active_policy_id
                WHERE r.region_id = ? AND p.status = 'ACTIVE'
                """, this::map, regionId);
        if (!active.isEmpty()) return active.get(0);

        RegionState region = regionState(regionId);
        Integer historyCount = jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_policy WHERE region_id = ?",
                Integer.class, regionId);
        if (historyCount != null && historyCount > 0) {
            throw new IllegalStateException("region has no active dispatch policy");
        }
        Instant now = Instant.now();
        PolicySelection initial = insertPolicy(regionId, region.nextVersion(), DispatchPolicy.demo(), "system", now, true);
        jdbc.update("UPDATE dispatch_policy_region SET active_policy_id = ?, next_version = ?, updated_at = ? WHERE region_id = ?",
                bytes(initial.id()), initial.version() + 1, Timestamp.from(now), regionId);
        audit("system", "DISPATCH_POLICY_DEFAULT_CREATED", initial, "ACTIVE", now);
        return initial;
    }

    @Override
    @Transactional(readOnly = true)
    public PolicySelection find(UUID policyId) {
        return jdbc.query("""
                SELECT id, version, region_id, config_json, status FROM dispatch_policy WHERE id = ?
                """, this::map, bytes(policyId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dispatch policy not found: " + policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicySelection> list(String regionId) {
        requireRegion(regionId);
        return jdbc.query("""
                SELECT id, version, region_id, config_json, status FROM dispatch_policy
                WHERE region_id = ? ORDER BY version DESC
                """, this::map, regionId);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicySelection findByVersion(String regionId, long version) {
        requireRegion(regionId);
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        return jdbc.query("""
                SELECT id, version, region_id, config_json, status FROM dispatch_policy
                WHERE region_id = ? AND version = ?
                """, this::map, regionId, version).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dispatch policy version not found"));
    }

    @Override
    @Transactional
    public PolicySelection create(String regionId, DispatchPolicy policy, String actorId, Instant now) {
        requireRegion(regionId);
        lockRegion(regionId, now);
        RegionState region = regionState(regionId);
        PolicySelection created = insertPolicy(regionId, region.nextVersion(), policy, actorId, now, false);
        jdbc.update("UPDATE dispatch_policy_region SET next_version = next_version + 1, updated_at = ? WHERE region_id = ?",
                Timestamp.from(now), regionId);
        audit(actorId, "DISPATCH_POLICY_CREATED", created, "DRAFT", now);
        return created;
    }

    @Override
    @Transactional
    public PolicySelection updateDraft(UUID policyId, DispatchPolicy policy, String actorId, Instant now) {
        String regionId = policyRegion(policyId);
        lockRegion(regionId, now);
        PolicySelection current = find(policyId);
        if (!"DRAFT".equals(current.status())) {
            throw new IllegalArgumentException("only a draft policy can be edited");
        }
        String config = write(policy);
        int updated = jdbc.update("""
                UPDATE dispatch_policy SET config_json = ?
                WHERE id = ? AND status = 'DRAFT'
                """, config, bytes(policyId));
        if (updated != 1) throw new IllegalArgumentException("only a draft policy can be edited");
        PolicySelection changed = new PolicySelection(current.id(), current.version(), regionId, policy, config, "DRAFT");
        audit(actorId, "DISPATCH_POLICY_DRAFT_UPDATED", changed, "DRAFT", now);
        return changed;
    }

    @Override
    @Transactional
    public PolicySelection activate(UUID policyId, String actorId, Instant now) {
        String regionId = policyRegion(policyId);
        lockRegion(regionId, now);
        PolicySelection target = find(policyId);
        if (!target.regionId().equals(regionId)) throw new IllegalArgumentException("policy region changed");
        if (!"DRAFT".equals(target.status())) throw new IllegalArgumentException("only a draft policy can be activated");
        jdbc.update("UPDATE dispatch_policy SET status = 'RETIRED' WHERE region_id = ? AND status = 'ACTIVE'",
                regionId);
        jdbc.update("UPDATE dispatch_policy SET status = 'ACTIVE', activated_at = ? WHERE id = ?",
                Timestamp.from(now), bytes(policyId));
        jdbc.update("UPDATE dispatch_policy_region SET active_policy_id = ?, updated_at = ? WHERE region_id = ?",
                bytes(policyId), Timestamp.from(now), regionId);
        audit(actorId, "DISPATCH_POLICY_ACTIVATED", target, "ACTIVE", now);
        return withStatus(target, "ACTIVE");
    }

    @Override
    @Transactional
    public PolicySelection deactivate(UUID policyId, String actorId, Instant now) {
        String regionId = policyRegion(policyId);
        lockRegion(regionId, now);
        PolicySelection target = find(policyId);
        if (!"ACTIVE".equals(target.status())) {
            throw new IllegalArgumentException("only an active policy can be deactivated");
        }
        jdbc.update("UPDATE dispatch_policy SET status = 'RETIRED' WHERE id = ?", bytes(policyId));
        jdbc.update("""
                UPDATE dispatch_policy_region SET active_policy_id = NULL, updated_at = ?
                WHERE region_id = ? AND active_policy_id = ?
                """, Timestamp.from(now), regionId, bytes(policyId));
        audit(actorId, "DISPATCH_POLICY_DEACTIVATED", target, "RETIRED", now);
        return withStatus(target, "RETIRED");
    }

    @Override
    @Transactional
    public PolicySelection rollback(String regionId, long version, String actorId, Instant now) {
        requireRegion(regionId);
        lockRegion(regionId, now);
        PolicySelection prior = jdbc.query("""
                SELECT id, version, region_id, config_json, status FROM dispatch_policy
                WHERE region_id = ? AND version = ?
                """, this::map, regionId, version).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dispatch policy version not found"));
        RegionState region = regionState(regionId);
        PolicySelection restored = insertPolicy(regionId, region.nextVersion(), prior.policy(), actorId, now, true);
        jdbc.update("UPDATE dispatch_policy SET status = 'RETIRED' WHERE region_id = ? AND status = 'ACTIVE' AND id <> ?",
                regionId, bytes(restored.id()));
        jdbc.update("UPDATE dispatch_policy_region SET active_policy_id = ?, next_version = next_version + 1, updated_at = ? WHERE region_id = ?",
                bytes(restored.id()), Timestamp.from(now), regionId);
        audit(actorId, "DISPATCH_POLICY_ROLLED_BACK", restored, "sourceVersion=" + version, now);
        return restored;
    }

    private void lockRegion(String regionId, Instant now) {
        jdbc.update("""
                INSERT INTO dispatch_policy_region (region_id, active_policy_id, next_version, updated_at)
                VALUES (?, NULL, 1, ?)
                ON DUPLICATE KEY UPDATE region_id = VALUES(region_id)
                """, regionId, Timestamp.from(now));
        regionState(regionId);
    }

    private RegionState regionState(String regionId) {
        return jdbc.query("SELECT next_version FROM dispatch_policy_region WHERE region_id = ? FOR UPDATE",
                (rs, row) -> new RegionState(rs.getLong(1)), regionId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("dispatch policy region lock is missing"));
    }

    private PolicySelection insertPolicy(String regionId, long version, DispatchPolicy policy,
                                         String actorId, Instant now, boolean active) {
        UUID id = UUID.randomUUID();
        String config = write(policy);
        jdbc.update("""
                INSERT INTO dispatch_policy (id, region_id, version, config_json, status, created_by, created_at, activated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(id), regionId, version, config, active ? "ACTIVE" : "DRAFT", actorId,
                Timestamp.from(now), active ? Timestamp.from(now) : null);
        if (active) {
            jdbc.update("""
                    UPDATE dispatch_policy_region SET active_policy_id = ?, updated_at = ?
                    WHERE region_id = ?
                    """, bytes(id), Timestamp.from(now), regionId);
        }
        return new PolicySelection(id, version, regionId, policy, config, active ? "ACTIVE" : "DRAFT");
    }

    private String policyRegion(UUID policyId) {
        return jdbc.query("SELECT region_id FROM dispatch_policy WHERE id = ?", (rs, row) -> rs.getString(1),
                bytes(policyId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dispatch policy not found: " + policyId));
    }

    private PolicySelection map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String config = rs.getString("config_json");
        return new PolicySelection(uuid(rs.getBytes("id")), rs.getLong("version"), rs.getString("region_id"),
                read(config), config, rs.getString("status"));
    }

    private static PolicySelection withStatus(PolicySelection selection, String status) {
        return new PolicySelection(selection.id(), selection.version(), selection.regionId(), selection.policy(),
                selection.configJson(), status);
    }

    private void audit(String actorId, String action, PolicySelection selection, String details, Instant now) {
        jdbc.update("""
                INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision,
                    details_redacted, created_at)
                VALUES (?, ?, ?, 'DISPATCH_POLICY', ?, 'APPLIED', ?, ?)
                """, bytes(UUID.randomUUID()), actorId, action, selection.id().toString(),
                SensitiveDataRedactor.redact("region=" + selection.regionId() + ";version="
                        + selection.version() + ";" + details),
                Timestamp.from(now));
    }

    private String write(DispatchPolicy policy) {
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("dispatch policy cannot be serialized", exception);
        }
    }

    private DispatchPolicy read(String config) {
        try {
            return objectMapper.readValue(config, DispatchPolicy.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted dispatch policy is invalid", exception);
        }
    }

    private static void requireRegion(String regionId) {
        if (regionId == null || !regionId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("regionId must be a simple identifier");
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record RegionState(long nextVersion) {
    }
}
