package com.peakdispatch.domain.dispatch;

public record DispatchPolicy(double etaWeight, double loadWeight, double urgencyWeight,
                             double maxPickupDistanceKm, long heartbeatTimeoutSeconds,
                             long offerTtlSeconds, int maxRounds, long[] retryBackoffSeconds) {
    public DispatchPolicy(double etaWeight, double loadWeight, double urgencyWeight,
                          double maxPickupDistanceKm, long heartbeatTimeoutSeconds,
                          long offerTtlSeconds) {
        this(etaWeight, loadWeight, urgencyWeight, maxPickupDistanceKm, heartbeatTimeoutSeconds,
                offerTtlSeconds, 5, new long[] {5, 15, 30, 60});
    }

    public DispatchPolicy {
        if (!Double.isFinite(etaWeight) || !Double.isFinite(loadWeight) || !Double.isFinite(urgencyWeight)
                || !Double.isFinite(maxPickupDistanceKm)
                || etaWeight < 0 || loadWeight < 0 || urgencyWeight < 0
                || !Double.isFinite(etaWeight + loadWeight + urgencyWeight)
                || etaWeight + loadWeight + urgencyWeight <= 0
                || maxPickupDistanceKm <= 0 || heartbeatTimeoutSeconds <= 0 || offerTtlSeconds <= 0
                || maxRounds < 1 || maxRounds > 5 || retryBackoffSeconds == null
                || retryBackoffSeconds.length < maxRounds - 1 || retryBackoffSeconds.length > 4) {
            throw new IllegalArgumentException("invalid dispatch policy");
        }
        retryBackoffSeconds = retryBackoffSeconds.clone();
        long previous = 0;
        for (long delay : retryBackoffSeconds) {
            if (delay <= 0 || delay < previous) throw new IllegalArgumentException("retry backoff must be positive and non-decreasing");
            previous = delay;
        }
    }

    public static DispatchPolicy demo() {
        return new DispatchPolicy(0.55, 0.25, 0.20, 20.0, 90, 30, 5, new long[] {5, 15, 30, 60});
    }

    public long retryDelaySeconds(int completedRound) {
        if (retryBackoffSeconds.length == 0) return 0;
        if (completedRound <= 0) return retryBackoffSeconds[0];
        return retryBackoffSeconds[Math.min(completedRound - 1, retryBackoffSeconds.length - 1)];
    }

    @Override
    public long[] retryBackoffSeconds() {
        return retryBackoffSeconds.clone();
    }
}
