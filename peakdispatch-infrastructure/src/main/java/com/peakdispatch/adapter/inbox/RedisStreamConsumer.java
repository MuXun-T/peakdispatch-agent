package com.peakdispatch.adapter.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.courier.CourierProjection;
import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import com.peakdispatch.adapter.redis.RedisKeyspace;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.courier.CourierPresence;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import java.io.IOException;

/** At-least-once Redis Streams consumer with DB Inbox leases and ACK-after-commit. */
@Component
@Profile("!test")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'API'")
public class RedisStreamConsumer {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumer.class);
    private static final long INBOX_LEASE_SECONDS = 60;
    private static final long CLAIM_IDLE_MILLIS = 5_000;

    @Value("${peakdispatch.worker.stream-batch-size:10}")
    private int batchSize = 10;

    private final StringRedisTemplate redis;
    private final InboxStore inbox;
    private final DispatchService dispatch;
    private final CourierRepository couriers;
    private final CourierProjection projection;
    private final ObjectMapper json;
    private final Clock clock;
    private final String consumerId = UUID.randomUUID().toString();
    private final String dispatchStream;
    private final String eventsStream;
    private final String dispatchGroup;
    private final String eventsGroup;
    private final String deadLetterStream;
    private final List<String> deadLetterKeys;
    private final DefaultRedisScript<String> publishDeadLetter;
    private final int maxAttempts;
    private final PeakDispatchMetrics metrics;
    private final Map<String, String> claimCursors = new HashMap<>();

    public RedisStreamConsumer(StringRedisTemplate redis, InboxStore inbox,
                               DispatchService dispatch, CourierRepository couriers,
                               CourierProjection projection, ObjectMapper json, Clock clock,
                               @Value("${peakdispatch.redis.environment:demo}") String environment) {
        this(redis, inbox, dispatch, couriers, projection, json, clock, environment, 5, null);
    }

    public RedisStreamConsumer(StringRedisTemplate redis, InboxStore inbox,
                               DispatchService dispatch, CourierRepository couriers,
                               CourierProjection projection, ObjectMapper json, Clock clock,
                               @Value("${peakdispatch.redis.environment:demo}") String environment,
                               @Value("${peakdispatch.worker.stream-max-attempts:5}") int maxAttempts) {
        this(redis, inbox, dispatch, couriers, projection, json, clock, environment, maxAttempts, null);
    }

    @Autowired
    public RedisStreamConsumer(StringRedisTemplate redis, InboxStore inbox,
                               DispatchService dispatch, CourierRepository couriers,
                               CourierProjection projection, ObjectMapper json, Clock clock,
                               @Value("${peakdispatch.redis.environment:demo}") String environment,
                               @Value("${peakdispatch.worker.stream-max-attempts:5}") int maxAttempts,
                               PeakDispatchMetrics metrics) {
        this.redis = redis;
        this.inbox = inbox;
        this.dispatch = dispatch;
        this.couriers = couriers;
        this.projection = projection;
        this.json = json;
        this.clock = clock;
        this.dispatchStream = "pd:" + environment + ":stream:dispatch";
        this.eventsStream = "pd:" + environment + ":stream:events";
        this.dispatchGroup = "peakdispatch-dispatch-v1";
        this.eventsGroup = "peakdispatch-events-v1";
        this.deadLetterKeys = RedisKeyspace.consumerDeadLetterKeys(environment);
        this.deadLetterStream = deadLetterKeys.getFirst();
        this.publishDeadLetter = script("redis/lua/publish-consumer-dlq.lua", String.class);
        if (maxAttempts < 1) throw new IllegalArgumentException("stream max attempts must be positive");
        this.maxAttempts = maxAttempts;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${peakdispatch.worker.stream-poll-delay-ms:250}")
    public synchronized void consumeBatch() {
        consume(dispatchStream, dispatchGroup);
        consume(eventsStream, eventsGroup);
    }

    private void consume(String stream, String group) {
        ensureGroup(stream, group);
        for (MapRecord<String, Object, Object> record : autoClaim(stream, group)) {
            process(stream, group, record);
        }
        List<MapRecord<String, Object, Object>> fresh = redis.opsForStream().read(
                Consumer.from(group, consumerId), StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(1)),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (fresh != null) {
            for (MapRecord<String, Object, Object> record : fresh) process(stream, group, record);
        }
    }

    private void ensureGroup(String stream, String group) {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
        } catch (RuntimeException failure) {
            if (!isBusyGroupFailure(failure)) throw failure;
        }
    }

    static boolean isBusyGroupFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT).contains("BUSYGROUP")) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private List<MapRecord<String, Object, Object>> autoClaim(String stream, String group) {
        String cursor = claimCursors.getOrDefault(stream, "0-0");
        Object response = redis.execute((RedisCallback<Object>) connection -> {
            return connection.execute("XAUTOCLAIM",
                    bytes(stream), bytes(group), bytes(consumerId), bytes(Long.toString(CLAIM_IDLE_MILLIS)),
                    bytes(cursor), bytes("COUNT"), bytes(Integer.toString(batchSize)));
        });
        if (!(response instanceof List<?> outer) || outer.size() < 2 || !(outer.get(1) instanceof List<?> entries)) {
            return List.of();
        }
        String nextCursor = text(outer.get(0));
        if ("0-0".equals(nextCursor)) claimCursors.remove(stream);
        else claimCursors.put(stream, nextCursor);
        List<MapRecord<String, Object, Object>> records = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof List<?> pair) || pair.size() < 2 || !(pair.get(1) instanceof List<?> fields)) continue;
            String id = text(pair.get(0));
            Map<Object, Object> values = new HashMap<>();
            for (int index = 0; index + 1 < fields.size(); index += 2) {
                values.put(text(fields.get(index)), text(fields.get(index + 1)));
            }
            MapRecord<String, Object, Object> decoded = MapRecord.create(stream, values).withId(RecordId.of(id));
            records.add(decoded);
        }
        return records;
    }

    private void process(String stream, String group, MapRecord<String, Object, Object> record) {
        String eventIdText = field(record, "eventId");
        UUID eventId;
        try {
            eventId = UUID.fromString(eventIdText);
        } catch (RuntimeException invalid) {
            log.error("Discarding malformed stream record {} from {}: missing eventId", record.getId(), stream);
            if (publishDeadLetter(record, stream, "MALFORMED_EVENT", null) != null) {
                if (metrics != null) metrics.streamEvent("dlq");
                ack(stream, group, record.getId());
            }
            return;
        }
        String inboxName = "peakdispatch:" + group;
        String owner = consumerId + ":" + record.getId().getValue();
        InboxStore.Claim claim = inbox.claim(inboxName, eventId, owner, Instant.now(clock), INBOX_LEASE_SECONDS);
        if (claim == InboxStore.Claim.PROCESSED) {
            ack(stream, group, record.getId());
            return;
        }
        if (claim == InboxStore.Claim.BUSY) return;
        try {
            Instant processingStarted = Instant.now(clock);
            String eventType = field(record, "eventType");
            if (metrics != null) {
                metrics.streamQueueWait(Duration.between(streamEnqueuedAt(record), processingStarted), eventType);
            }
            handle(record);
            if (metrics != null && isDispatchEvent(eventType)) {
                metrics.dispatchEventToResult(Duration.between(outboxCreatedAt(record), Instant.now(clock)));
            }
            if (inbox.processed(inboxName, eventId, owner, Instant.now(clock))) {
                ack(stream, group, record.getId());
            }
        } catch (RuntimeException failure) {
            if (metrics != null) metrics.streamEvent("retry");
            try {
                String code = failure.getClass().getSimpleName().toUpperCase();
                Instant failedAt = Instant.now(clock);
                int attempts = inbox.failed(inboxName, eventId, owner,
                        code.substring(0, Math.min(64, code.length())), failedAt);
                if (attempts >= maxAttempts) {
                    String deadLetterId = publishDeadLetter(record, stream, code, eventId);
                    if (deadLetterId != null && inbox.deadLettered(inboxName, eventId, owner, deadLetterId, failedAt)) {
                        if (metrics != null) metrics.streamEvent("dlq");
                        ack(stream, group, record.getId());
                    }
                }
            } catch (RuntimeException databaseFailure) {
                failure.addSuppressed(databaseFailure);
            }
            log.warn("Stream event {} remains pending after {}", eventId, failure.getClass().getSimpleName());
        }
    }

    private Instant streamEnqueuedAt(MapRecord<String, Object, Object> record) {
        try {
            long timestampMillis = Long.parseLong(record.getId().getValue().split("-", 2)[0]);
            return Instant.ofEpochMilli(timestampMillis);
        } catch (RuntimeException invalidId) {
            return Instant.now(clock);
        }
    }

    private Instant outboxCreatedAt(MapRecord<String, Object, Object> record) {
        try {
            return Instant.parse(field(record, "outboxCreatedAt"));
        } catch (RuntimeException missingTimestamp) {
            return streamEnqueuedAt(record);
        }
    }

    private static boolean isDispatchEvent(String eventType) {
        return "order.dispatch_requested".equals(eventType) || "dispatch.no_candidate".equals(eventType);
    }

    private void handle(MapRecord<String, Object, Object> record) {
        String eventType = field(record, "eventType");
        String aggregateId = field(record, "aggregateId");
        UUID id = UUID.fromString(aggregateId);
        switch (eventType) {
            case "order.created" -> dispatch.advanceCreatedOrder(id);
            case "order.dispatch_requested", "dispatch.no_candidate" -> dispatch.tryDispatch(id);
            case "courier.presence_changed" -> couriers.find(id).ifPresent(snapshot -> {
                String previousRegion = parsePreviousRegion(field(record, "payload"));
                if (snapshot.onlineState() == CourierOnlineState.ONLINE) {
                    projection.projectSession(snapshot, previousRegion);
                } else {
                    projection.removeSession(snapshot, previousRegion);
                }
            });
            case "courier.capacity_changed" -> couriers.find(id).ifPresent(snapshot -> {
                UUID offerId = parseOfferId(field(record, "payload"));
                projection.projectCapacity(snapshot, offerId);
            });
            default -> { }
        }
    }

    private UUID parseOfferId(String payload) {
        try {
            JsonNode offerId = json.readTree(payload).get("offerId");
            return offerId == null || offerId.isNull() ? null : UUID.fromString(offerId.asText());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid capacity event payload", invalid);
        }
    }

    private String parsePreviousRegion(String payload) {
        try {
            JsonNode value = json.readTree(payload).get("previousRegionId");
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid courier presence event payload", invalid);
        }
    }

    protected void ack(String stream, String group, RecordId recordId) {
        redis.opsForStream().acknowledge(stream, group, recordId);
    }

    private String publishDeadLetter(MapRecord<String, Object, Object> record, String sourceStream,
                                     String failureCode, UUID eventId) {
        return writeDeadLetter(record, sourceStream, failureCode, eventId);
    }

    protected String writeDeadLetter(MapRecord<String, Object, Object> record, String sourceStream,
                                     String failureCode, UUID eventId) {
        Map<String, String> values = new HashMap<>();
        record.getValue().forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        values.put("failureCode", failureCode);
        values.put("sourceStream", sourceStream);
        values.put("sourceRecordId", record.getId().getValue());
        if (eventId != null) values.put("eventId", eventId.toString());
        try {
            RedisKeyspace.requireSameSlot(deadLetterKeys);
            List<String> arguments = new ArrayList<>();
            arguments.add(sourceStream + "|" + record.getId().getValue());
            new java.util.TreeMap<>(values).forEach((key, value) -> {
                arguments.add(key);
                arguments.add(value);
            });
            return redis.execute(publishDeadLetter, deadLetterKeys, arguments.toArray(Object[]::new));
        } catch (RuntimeException failure) {
            log.warn("Consumer DLQ unavailable for stream record {} ({})", record.getId(),
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    private static String field(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        return value == null ? "" : value.toString();
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new DefaultRedisScript<>(StreamUtils.copyToString(input, StandardCharsets.UTF_8), resultType);
        } catch (IOException failure) {
            throw new IllegalStateException("Redis Lua script is missing: " + path, failure);
        }
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }
}
