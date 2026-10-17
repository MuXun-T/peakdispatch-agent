package com.peakdispatch.application.courier;

import java.util.UUID;

public record CourierIndexEntry(UUID courierId, long presenceGeneration, long sequence,
                                double latitude, double longitude, double distanceKm) {
}
