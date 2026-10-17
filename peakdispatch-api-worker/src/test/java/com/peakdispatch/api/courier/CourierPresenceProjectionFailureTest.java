package com.peakdispatch.api.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.peakdispatch.application.courier.CourierPresenceService;
import com.peakdispatch.application.courier.CourierProjection;
import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.courier.CourierPresence;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CourierPresenceProjectionFailureTest {
    @Test
    void committedPresenceCommandRemainsSuccessfulWhenRedisProjectionFails() {
        CourierRepository couriers = mock(CourierRepository.class);
        CourierProjection projection = mock(CourierProjection.class);
        UUID courierId = UUID.randomUUID();
        CourierPresence snapshot = new CourierPresence(courierId, "region-a", CourierOnlineState.ONLINE,
                4, 0, null, null, null, 3, 0, 0, 0, Instant.now());
        when(couriers.setOnline(eq(courierId), eq("region-a"), any())).thenReturn(snapshot);
        doThrow(new IllegalStateException("redis unavailable")).when(projection).projectSession(snapshot);

        CourierPresence result = new CourierPresenceService(couriers, Optional.of(projection), Clock.systemUTC())
                .setOnline(courierId, "region-a");

        assertThat(result).isEqualTo(snapshot);
        verify(couriers).invalidateStaleOffers(eq(courierId), eq(snapshot.presenceGeneration()), any(),
                eq("COURIER_SESSION_CHANGED"));
        verify(projection).projectSession(snapshot);
    }
}
