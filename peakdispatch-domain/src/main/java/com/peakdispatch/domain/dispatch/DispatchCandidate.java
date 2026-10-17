package com.peakdispatch.domain.dispatch;

import java.time.Instant;
import java.util.UUID;

/** Deterministic, explainable candidate score used by the demo dispatcher. */
public record DispatchCandidate(UUID courierId, double score, Instant estimatedDeliveryAt,
                                double distanceKm, double loadRatio, String reason) {
}
