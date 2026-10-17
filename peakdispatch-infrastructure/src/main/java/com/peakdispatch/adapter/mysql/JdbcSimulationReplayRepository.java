package com.peakdispatch.adapter.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.operations.SimulationReplayRepository;
import com.peakdispatch.application.operations.SimulationReplayService;
import com.peakdispatch.application.security.SensitiveDataRedactor;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSimulationReplayRepository implements SimulationReplayRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSimulationReplayRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(SimulationReplayService.ReplayView replay, String configSnapshot,
                     String resultJson, String actorId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO operations_replay
                    (id, region_id, scenario_id, seed, policy_id, policy_version, config_snapshot,
                     result_json, result_hash, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(replay.replayId()), replay.regionId(), replay.scenarioId(), replay.seed(),
                bytes(replay.policyId()), replay.policyVersion(), configSnapshot, resultJson,
                replay.resultHash(), actorId, Timestamp.from(createdAt));
        audit(actorId, "OPS_REPLAY_CREATED", replay.regionId(), replay.replayId().toString(), "APPLIED",
                "seed=" + replay.seed() + ";resultHash=" + replay.resultHash(), null, createdAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SimulationReplayService.StoredReplay> find(UUID replayId) {
        return jdbc.query("""
                SELECT id, region_id, created_by, created_at, result_hash, result_json
                FROM operations_replay WHERE id = ?
                """, (rs, row) -> {
            try {
                return new SimulationReplayService.StoredReplay(uuid(rs.getBytes("id")), rs.getString("region_id"),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("result_hash"), mapper.readValue(rs.getString("result_json"),
                        SimulationReplayService.ReplayReport.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("persisted replay result is invalid", exception);
            }
        }, bytes(replayId)).stream().findFirst();
    }

    @Override
    @Transactional
    public void audit(String actorId, String action, String regionId, String targetId, String decision,
                      String details, String traceId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_log (id, actor_id, action, target_type, target_id, decision,
                    trace_id, details_redacted, created_at)
                VALUES (?, ?, ?, 'SIMULATION_REPLAY', ?, ?, ?, ?, ?)
                """, bytes(UUID.randomUUID()), actorId, action, targetId, decision,
                traceId, SensitiveDataRedactor.redact("region=" + regionId + ";" + details),
                Timestamp.from(occurredAt));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
