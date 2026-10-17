package com.peakdispatch.application.operations;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SimulationReplayRepository {
    void save(SimulationReplayService.ReplayView replay, String configSnapshot, String resultJson,
              String actorId, Instant createdAt);

    Optional<SimulationReplayService.StoredReplay> find(UUID replayId);

    void audit(String actorId, String action, String regionId, String targetId, String decision,
               String details, String traceId, Instant occurredAt);
}
