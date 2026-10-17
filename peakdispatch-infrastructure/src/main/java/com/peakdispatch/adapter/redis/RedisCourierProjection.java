package com.peakdispatch.adapter.redis;

import com.peakdispatch.application.courier.CourierIndexEntry;
import com.peakdispatch.application.courier.CourierProjection;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.courier.CourierPresence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/** Redis projections are disposable; every repair is rebuilt from the MySQL snapshot. */
@Component
@Profile("!test")
public class RedisCourierProjection implements CourierProjection {
    private static final Logger log = LoggerFactory.getLogger(RedisCourierProjection.class);
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final long PRESENCE_HEARTBEAT_TIMEOUT_SECONDS = 90;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String environment;
    private final DefaultRedisScript<Long> startPresence;
    private final DefaultRedisScript<Long> updatePresence;
    private final DefaultRedisScript<Long> indexPresence;
    private final DefaultRedisScript<Long> fencePresence;
    private final DefaultRedisScript<Long> reserveLease;
    private final DefaultRedisScript<Long> releaseLease;
    private final DefaultRedisScript<Long> projectCapacity;
    private final DefaultRedisScript<Long> stopPresence;
    private final DefaultRedisScript<Long> pruneHeartbeats;
    private final PeakDispatchMetrics metrics;

    public RedisCourierProjection(StringRedisTemplate redis, JdbcTemplate jdbc, Clock clock,
                                  @Value("${peakdispatch.redis.environment:demo}") String environment) {
        this(redis, jdbc, clock, environment, null);
    }

    @Autowired
    public RedisCourierProjection(StringRedisTemplate redis, JdbcTemplate jdbc, Clock clock,
                                  @Value("${peakdispatch.redis.environment:demo}") String environment,
                                  ObjectProvider<PeakDispatchMetrics> metricsProvider) {
        if (environment == null || !environment.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Redis environment must be a simple identifier");
        }
        this.redis = redis;
        this.jdbc = jdbc;
        this.clock = clock;
        this.environment = environment;
        this.startPresence = script("redis/lua/start-presence.lua");
        this.updatePresence = script("redis/lua/update-presence.lua");
        this.indexPresence = script("redis/lua/index-presence.lua");
        this.fencePresence = script("redis/lua/fence-presence.lua");
        this.reserveLease = script("redis/lua/reserve-lease.lua");
        this.releaseLease = script("redis/lua/release-lease.lua");
        this.projectCapacity = script("redis/lua/project-capacity.lua");
        this.stopPresence = script("redis/lua/stop-presence.lua");
        this.pruneHeartbeats = script("redis/lua/prune-heartbeats.lua");
        this.metrics = metricsProvider == null ? null : metricsProvider.getIfAvailable();
    }

    @Override
    public void projectSession(CourierPresence presence) {
        projectSession(presence, null);
    }

    @Override
    public void projectSession(CourierPresence presence, String previousRegionId) {
        if (presence.onlineState() != CourierOnlineState.ONLINE || presence.regionId() == null) {
            removeSession(presence, previousRegionId);
            return;
        }
        String presenceKey = presenceKey(presence.courierId());
        Map<Object, Object> previous = redis.opsForHash().entries(presenceKey);
        String previousRegion = value(previous.get("region"));
        long previousGeneration = number(previous.get("generation"), -1);
        if (previousRegion != null && (previousGeneration < presence.presenceGeneration()
                || !previousRegion.equals(presence.regionId()))) {
            long fenceSequence = previousGeneration == presence.presenceGeneration()
                    ? number(previous.get("sequence"), 0) : 0;
            fence(previousRegion, presence.courierId(), presence.presenceGeneration(), fenceSequence);
        }
        if (previousRegionId != null && !previousRegionId.equals(previousRegion)) {
            fence(previousRegionId, presence.courierId(), presence.presenceGeneration(), 0);
        }
        List<String> keys = List.of(presenceKey);
        RedisKeyspace.requireSameSlot(keys);
        Long result = redis.execute(startPresence, keys,
                Long.toString(presence.presenceGeneration()), safeRegion(presence.regionId()));
        if (result == null || result < 0) {
            throw new IllegalStateException("Redis rejected courier session projection: " + result);
        }
        if (presence.lastHeartbeatAt() != null && presence.latitude() != null && presence.longitude() != null
                && presence.lastHeartbeatAt().plusSeconds(PRESENCE_HEARTBEAT_TIMEOUT_SECONDS)
                .isAfter(Instant.now(clock))) {
            applyHeartbeatAndIndex(presence, true);
        } else {
            fence(presence.regionId(), presence.courierId(), presence.presenceGeneration(), presence.sequence());
        }
        projectCapacity(presence, null);
    }

    @Override
    public void projectHeartbeat(CourierPresence presence) {
        if (presence.onlineState() != CourierOnlineState.ONLINE || presence.regionId() == null
                || presence.latitude() == null || presence.longitude() == null
                || presence.lastHeartbeatAt() == null) {
            throw new IllegalArgumentException("only a complete online heartbeat can be projected");
        }
        applyHeartbeatAndIndex(presence, false);
        projectCapacity(presence, null);
    }

    private void applyHeartbeatAndIndex(CourierPresence presence, boolean restoreDatabaseTimestamp) {
        String mode = restoreDatabaseTimestamp ? "RESTORE" : "LIVE";
        List<String> presenceKeys = List.of(presenceKey(presence.courierId()));
        RedisKeyspace.requireSameSlot(presenceKeys);
        Long result = redis.execute(updatePresence, presenceKeys,
                Long.toString(presence.presenceGeneration()), safeRegion(presence.regionId()),
                Long.toString(presence.sequence()), Double.toString(presence.latitude()),
                Double.toString(presence.longitude()), mode, Long.toString(presence.lastHeartbeatAt().toEpochMilli()));
        if (result == null || result < 1) {
            if (result != null && (result == -2 || result == -4)) {
                Map<Object, Object> current = redis.opsForHash().entries(presenceKey(presence.courierId()));
                long currentGeneration = number(current.get("generation"), -1);
                long currentSequence = number(current.get("sequence"), -1);
                if (currentGeneration > presence.presenceGeneration()
                        || (currentGeneration == presence.presenceGeneration()
                        && currentSequence > presence.sequence())) {
                    return;
                }
            }
            throw new IllegalStateException("Redis rejected courier heartbeat projection: " + result);
        }
        Object heartbeat = redis.opsForHash().get(presenceKey(presence.courierId()), "heartbeatMs");
        if (heartbeat == null) {
            throw new IllegalStateException("Redis heartbeat timestamp is missing after projection");
        }
        String key = regionKey(presence.regionId());
        List<String> regionKeys = RedisKeyspace.regionIndexKeys(environment, presence.regionId());
        RedisKeyspace.requireSameSlot(regionKeys);
        Long indexed = redis.execute(indexPresence, regionKeys,
                Long.toString(presence.presenceGeneration()), Long.toString(presence.sequence()),
                presence.courierId().toString(), Double.toString(presence.longitude()),
                Double.toString(presence.latitude()), heartbeat.toString());
        if (indexed == null || indexed < 0) {
            throw new IllegalStateException("Redis rejected courier GEO projection for " + key);
        }
    }

    @Override
    public void removeSession(CourierPresence presence) {
        removeSession(presence, null);
    }

    @Override
    public void removeSession(CourierPresence presence, String previousRegionId) {
        String presenceKey = presenceKey(presence.courierId());
        Map<Object, Object> previous = redis.opsForHash().entries(presenceKey);
        String previousRegion = value(previous.get("region"));
        long previousGeneration = number(previous.get("generation"), -1);
        List<String> keys = List.of(presenceKey);
        RedisKeyspace.requireSameSlot(keys);
        Long stopped = redis.execute(stopPresence, keys,
                Long.toString(presence.presenceGeneration()));
        if (stopped != null && stopped < 0) {
            throw new IllegalStateException("Redis refused to apply courier presence tombstone: " + stopped);
        }
        if (previousRegion != null) {
            long fenceGeneration = Math.max(presence.presenceGeneration(), previousGeneration);
            fence(previousRegion, presence.courierId(), fenceGeneration, 0);
        }
        if (previousRegionId != null && !previousRegionId.equals(previousRegion)) {
            fence(previousRegionId, presence.courierId(), presence.presenceGeneration(), 0);
        }
        projectCapacity(presence, null);
    }

    @Override
    public void projectCapacity(CourierPresence presence, UUID confirmedOfferId) {
        String offerId = confirmedOfferId == null ? "" : confirmedOfferId.toString();
        List<String> keys = RedisKeyspace.riderKeys(environment, presence.courierId());
        RedisKeyspace.requireSameSlot(keys);
        Long result = redis.execute(projectCapacity, keys.subList(1, 4),
                Long.toString(presence.capacityVersion()), Integer.toString(presence.committedCount()),
                Integer.toString(presence.reservedCount()), Integer.toString(presence.capacityLimit()), offerId);
        if (result == null) {
            throw new IllegalStateException("Redis capacity projection returned no result");
        }
    }

    @Override
    public List<CourierIndexEntry> nearby(String regionId, double latitude, double longitude,
                                          double radiusKm, long heartbeatTimeoutSeconds) {
        safeRegion(regionId);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || radiusKm <= 0) {
            throw new IllegalArgumentException("invalid GEO search coordinates or radius");
        }
        List<String> keys = RedisKeyspace.regionIndexKeys(environment, regionId);
        RedisKeyspace.requireSameSlot(keys);
        Long expired = redis.execute(pruneHeartbeats, keys,
                Long.toString(heartbeatTimeoutSeconds * 1000), "100");
        if (metrics != null && expired != null) metrics.heartbeatExpired(expired);
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().radius(
                geoKey(regionId), new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS)));
        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }
        long earliest = Instant.now(clock).minusSeconds(heartbeatTimeoutSeconds).toEpochMilli();
        List<CourierIndexEntry> candidates = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            String member = result.getContent().getName();
            UUID courierId;
            try {
                courierId = UUID.fromString(member);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Object indexedVersion = redis.opsForHash().get(versionsKey(regionId), member);
            Object heartbeat = redis.opsForZSet().score(heartbeatsKey(regionId), member);
            Map<Object, Object> presence = redis.opsForHash().entries(presenceKey(courierId));
            if (indexedVersion == null || heartbeat == null || number(heartbeat, 0) < earliest
                    || !"1".equals(value(presence.get("online")))
                    || !safeRegion(regionId).equals(value(presence.get("region")))) {
                continue;
            }
            long generation = number(presence.get("generation"), -1);
            long sequence = number(presence.get("sequence"), -1);
            if (!(generation + ":" + sequence).equals(indexedVersion.toString())
                    || number(presence.get("heartbeatMs"), 0) < earliest) {
                continue;
            }
            Double lat = decimal(presence.get("latitude"));
            Double lon = decimal(presence.get("longitude"));
            if (lat == null || lon == null) continue;
            double distance = distanceKm(latitude, longitude, lat, lon);
            candidates.add(new CourierIndexEntry(courierId, generation, sequence, lat, lon, distance));
        }
        return List.copyOf(candidates);
    }

    @Override
    public Lease reserve(CourierPresence courier, UUID offerId, String tokenHash, long heartbeatTimeoutSeconds,
                         long leaseTtlMillis) {
        if (courier.regionId() == null || courier.latitude() == null || courier.longitude() == null
                || leaseTtlMillis < 100 || leaseTtlMillis > 60_000 || heartbeatTimeoutSeconds < 1) {
            throw new IllegalArgumentException("invalid courier lease request");
        }
        String presence = presenceKey(courier.courierId());
        String capacity = capacityKey(courier.courierId());
        String leases = leasesKey(courier.courierId());
        String expiry = leaseExpiryKey(courier.courierId());
        List<String> keys = List.of(presence, capacity, leases, expiry);
        RedisKeyspace.requireSameSlot(keys);
        Long result = redis.execute(reserveLease, keys,
                offerId.toString(), tokenHash, Integer.toString(courier.capacityLimit()),
                Long.toString(heartbeatTimeoutSeconds * 1000), Long.toString(leaseTtlMillis),
                safeRegion(courier.regionId()), Long.toString(courier.presenceGeneration()),
                Long.toString(courier.sequence()), Long.toString(courier.capacityVersion()));
        if (result != null && (result == 1 || result == 2)) {
            return new Lease(courier.courierId(), offerId, tokenHash,
                    Instant.now(clock).toEpochMilli() + leaseTtlMillis);
        }
        if (result != null && result == -4) {
            projectCapacity(courier, null);
        }
        return null;
    }

    @Override
    public void release(Lease lease) {
        List<String> keys = List.of(leasesKey(lease.courierId()), leaseExpiryKey(lease.courierId()));
        RedisKeyspace.requireSameSlot(keys);
        redis.execute(releaseLease, keys,
                lease.offerId().toString(), lease.tokenHash());
    }

    @Override
    public void confirm(Lease lease, CourierPresence committedSnapshot) {
        projectCapacity(committedSnapshot, lease.offerId());
    }

    @Override
    public void reconcile(CourierPresence databaseSnapshot) {
        Map<Object, Object> capacity = redis.opsForHash().entries(capacityKey(databaseSnapshot.courierId()));
        boolean capacityDiffers = capacity.isEmpty()
                || number(capacity.get("version"), -1) != databaseSnapshot.capacityVersion()
                || number(capacity.get("committed"), -1) != databaseSnapshot.committedCount()
                || number(capacity.get("reserved"), -1) != databaseSnapshot.reservedCount()
                || number(capacity.get("limit"), -1) != databaseSnapshot.capacityLimit()
                || !"1".equals(value(capacity.get("ready")));
        Map<Object, Object> presence = redis.opsForHash().entries(presenceKey(databaseSnapshot.courierId()));
        boolean presenceDiffers = presence.isEmpty()
                || number(presence.get("generation"), -1) != databaseSnapshot.presenceGeneration()
                || number(presence.get("sequence"), -1) != databaseSnapshot.sequence()
                || !java.util.Objects.equals(databaseSnapshot.regionId(), value(presence.get("region")))
                || !"1".equals(value(presence.get("online")))
                || !sameCoordinate(presence.get("latitude"), databaseSnapshot.latitude())
                || !sameCoordinate(presence.get("longitude"), databaseSnapshot.longitude())
                || number(presence.get("heartbeatMs"), 0) != epochMillis(databaseSnapshot.lastHeartbeatAt());
        if (presenceDiffers) {
            audit(databaseSnapshot, capacity, "PRESENCE_REBUILT");
            projectSession(databaseSnapshot);
        } else if (databaseSnapshot.lastHeartbeatAt() != null && databaseSnapshot.latitude() != null
                && databaseSnapshot.longitude() != null
                && !databaseSnapshot.lastHeartbeatAt().plusSeconds(90).isBefore(Instant.now(clock))) {
            applyHeartbeatAndIndex(databaseSnapshot, true);
        }
        if (capacityDiffers) {
            audit(databaseSnapshot, capacity, "CAPACITY_REBUILT");
            projectCapacity(databaseSnapshot, null);
        }
    }

    private void audit(CourierPresence snapshot, Map<Object, Object> redisCapacity, String action) {
        jdbc.update("""
                INSERT INTO redis_projection_reconciliation
                    (id, courier_id, presence_generation, capacity_version, db_committed, db_reserved, db_limit,
                     redis_committed, redis_reserved, redis_limit, action, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, uuidBytes(UUID.randomUUID()), uuidBytes(snapshot.courierId()), snapshot.presenceGeneration(),
                snapshot.capacityVersion(), snapshot.committedCount(), snapshot.reservedCount(), snapshot.capacityLimit(),
                nullableInteger(redisCapacity.get("committed")), nullableInteger(redisCapacity.get("reserved")),
                nullableInteger(redisCapacity.get("limit")), action, Timestamp.from(Instant.now(clock)));
    }

    private void fence(String regionId, UUID courierId, long generation, long sequence) {
        List<String> keys = RedisKeyspace.regionIndexKeys(environment, regionId);
        RedisKeyspace.requireSameSlot(keys);
        redis.execute(fencePresence, keys,
                Long.toString(generation), Long.toString(sequence), courierId.toString());
    }

    private static DefaultRedisScript<Long> script(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            String source = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, Long.class);
        } catch (IOException failure) {
            throw new IllegalStateException("Redis Lua script is missing: " + path, failure);
        }
    }

    private String presenceKey(UUID courierId) { return RedisKeyspace.riderKeys(environment, courierId).get(0); }
    private String capacityKey(UUID courierId) { return RedisKeyspace.riderKeys(environment, courierId).get(1); }
    private String leasesKey(UUID courierId) { return RedisKeyspace.riderKeys(environment, courierId).get(2); }
    private String leaseExpiryKey(UUID courierId) { return RedisKeyspace.riderKeys(environment, courierId).get(3); }
    private String regionKey(String regionId) { return "pd:" + environment + ":geo:" + RedisKeyspace.regionTag(environment, regionId); }
    private String geoKey(String regionId) { return regionKey(regionId) + ":members"; }
    private String versionsKey(String regionId) { return regionKey(regionId) + ":versions"; }
    private String heartbeatsKey(String regionId) { return regionKey(regionId) + ":heartbeats"; }

    private static String safeRegion(String regionId) { return RedisKeyspace.validateRegion(regionId); }

    private static String value(Object value) { return value == null ? null : value.toString(); }
    static long number(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try { return new java.math.BigDecimal(value.toString()).longValue(); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static boolean sameCoordinate(Object value, Double expected) {
        if (expected == null) return value == null;
        Double actual = decimal(value);
        return actual != null && Double.compare(actual, expected) == 0;
    }
    private static long epochMillis(Instant value) { return value == null ? 0 : value.toEpochMilli(); }
    private static Double decimal(Object value) {
        if (value == null) return null;
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException ignored) { return null; }
    }
    private static Integer nullableInteger(Object value) {
        return value == null ? null : (int) number(value, 0);
    }
    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
    private static byte[] uuidBytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
