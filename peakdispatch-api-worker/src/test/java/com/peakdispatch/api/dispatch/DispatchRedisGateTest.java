package com.peakdispatch.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.dispatch.DispatchPolicyProvider;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.application.dispatch.OfferTokenProtector;
import com.peakdispatch.application.dispatch.RedisAvailability;
import com.peakdispatch.domain.dispatch.DispatchPolicy;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DispatchRedisGateTest {

    @Test
    void tryDispatch_whenRedisUnavailable_recordsRecoveryStateWithoutOffer() {
        DispatchRepository dispatch = mock(DispatchRepository.class);
        CourierRepository couriers = mock(CourierRepository.class);
        OfferTokenProtector tokens = mock(OfferTokenProtector.class);
        DispatchPolicyProvider policies = mock(DispatchPolicyProvider.class);
        RedisAvailability redis = mock(RedisAvailability.class);
        Instant now = Instant.parse("2030-01-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        UUID orderId = UUID.randomUUID();
        DeliveryOrder order = new DeliveryOrder(orderId, UUID.randomUUID(), "demo-region",
                30.25, 120.16, 30.251, 120.161, now.plusSeconds(300), now.plusSeconds(3600),
                OrderStatus.WAITING_DISPATCH, 2, now, now);
        DispatchPolicyProvider.PolicySelection policy = new DispatchPolicyProvider.PolicySelection(
                UUID.randomUUID(), 1, "demo-region", DispatchPolicy.demo(), "{}");
        when(dispatch.findWaitingOrder(orderId, now)).thenReturn(Optional.of(order));
        when(policies.active("demo-region")).thenReturn(policy);
        when(redis.available()).thenReturn(false);

        DispatchService service = new DispatchService(dispatch, couriers, tokens, policies, redis, clock, true, 5);
        DispatchService.DispatchDecision result = service.tryDispatch(orderId);

        assertThat(result.outcome()).isEqualTo("REDIS_UNAVAILABLE");
        assertThat(result.offer()).isNull();
        verify(dispatch).recordDispatchUnavailable(eq(order), eq(now), eq(now.plusSeconds(5)), eq(policy));
        verifyNoInteractions(couriers, tokens);
    }
}
