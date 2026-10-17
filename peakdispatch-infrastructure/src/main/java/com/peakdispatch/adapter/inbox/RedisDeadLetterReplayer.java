package com.peakdispatch.adapter.inbox;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Replays one consumer-DLQ record only after resetting its durable Inbox state. */
@Component
@Profile("!test")
public class RedisDeadLetterReplayer {
    private final StringRedisTemplate redis;
    private final InboxStore inbox;
    private final Clock clock;

    public RedisDeadLetterReplayer(StringRedisTemplate redis, InboxStore inbox, Clock clock) {
        this.redis = redis;
        this.inbox = inbox;
        this.clock = clock;
    }

    public boolean replay(MapRecord<String, Object, Object> deadLetter, String consumerName, String dlqGroup) {
        String sourceStream = value(deadLetter, "sourceStream");
        String eventIdText = value(deadLetter, "eventId");
        UUID eventId;
        try {
            eventId = UUID.fromString(eventIdText);
        } catch (RuntimeException invalid) {
            return false;
        }
        Instant now = Instant.now(clock);
        if (!inbox.requeueDeadLetter(consumerName, eventId, "dlq-replay", now)) return false;
        Map<String, String> event = new HashMap<>();
        deadLetter.getValue().forEach((key, value) -> event.put(String.valueOf(key), String.valueOf(value)));
        event.remove("failureCode");
        event.remove("sourceStream");
        event.remove("sourceRecordId");
        try {
            redis.opsForStream().add(MapRecord.create(sourceStream, event));
        } catch (RuntimeException failure) {
            inbox.undoDeadLetterReplay(consumerName, eventId, Instant.now(clock));
            return false;
        }
        if (!inbox.releaseDeadLetterReplay(consumerName, eventId, Instant.now(clock))) return false;
        try {
            redis.opsForStream().acknowledge(deadLetter.getStream(), dlqGroup, deadLetter.getId());
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String value(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        return value == null ? "" : value.toString();
    }
}
