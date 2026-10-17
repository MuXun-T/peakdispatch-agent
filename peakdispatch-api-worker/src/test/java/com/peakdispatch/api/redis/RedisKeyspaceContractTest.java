package com.peakdispatch.api.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakdispatch.adapter.redis.RedisKeyspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedisKeyspaceContractTest {
    @Test
    void riderRegionAndOfferDelayKeysUseEntityScopedHashTags() {
        UUID courier = UUID.fromString("00000000-0000-0000-0000-000000000202");
        List<String> rider = RedisKeyspace.riderKeys("it", courier);
        List<String> region = RedisKeyspace.regionIndexKeys("it", "demo-region");
        List<String> offers = RedisKeyspace.offerDelayKeys("it");
        List<String> dlq = RedisKeyspace.consumerDeadLetterKeys("it");

        assertSameSlot(rider);
        assertSameSlot(region);
        assertSameSlot(offers);
        assertSameSlot(dlq);
        assertThat(rider).allMatch(key -> key.startsWith("pd:it:courier:{it:"));
        assertThat(region).allMatch(key -> key.startsWith("pd:it:geo:{it:demo-region}"));
        assertThat(offers).allMatch(key -> key.startsWith("pd:it:offers:{it-offers}"));
        assertThat(dlq).allMatch(key -> key.startsWith("pd:it:stream:dlq:consumer:{it-consumer-dlq}"));
        assertThat(rider.getFirst()).doesNotContain("{it}:");
    }

    @Test
    void invalidUserSuppliedKeyComponentsAreRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> RedisKeyspace.regionIndexKeys("it", "region}other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> RedisKeyspace.riderKeys("it:bad", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertSameSlot(List<String> keys) {
        assertThat(keys.stream().map(RedisKeyspace::keySlot).distinct()).hasSize(1);
    }
}
