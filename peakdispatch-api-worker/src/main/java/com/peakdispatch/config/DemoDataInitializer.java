package com.peakdispatch.config;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Small deterministic fixture for the local demo and H2 test profile. */
@Component
@Profile({"demo", "test"})
public class DemoDataInitializer {
    public static final UUID DEMO_MERCHANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID DEMO_COURIER_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    public static final String DEMO_REGION_ID = "demo-region";

    public DemoDataInitializer(JdbcTemplate jdbc, Clock clock, DemoUsersProperties users) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM merchant WHERE id = ?", Integer.class,
                uuidBytes(DEMO_MERCHANT_ID));
        if (count == null || count == 0) {
            jdbc.update("""
                    INSERT INTO merchant (id, username, region_id, active, prep_state, version, avg_prep_seconds, updated_at)
                    VALUES (?, ?, ?, TRUE, 'OPEN', 0, 900, ?)
                    """, uuidBytes(DEMO_MERCHANT_ID), users.users().get("merchant").username(), DEMO_REGION_ID,
                    Timestamp.from(Instant.now(clock)));
        } else {
            jdbc.update("UPDATE merchant SET username = ? WHERE id = ? AND username IS NULL",
                    users.users().get("merchant").username(), uuidBytes(DEMO_MERCHANT_ID));
        }
        Integer courierCount = jdbc.queryForObject("SELECT COUNT(*) FROM courier WHERE id = ?", Integer.class,
                uuidBytes(DEMO_COURIER_ID));
        if (courierCount == null || courierCount == 0) {
            String courierUsername = users.users().get("courier").username();
            jdbc.update("""
                    INSERT INTO courier (id, username, region_id, online_state, presence_generation,
                                         capacity_limit, version)
                    VALUES (?, ?, NULL, 'OFFLINE', 0, 3, 0)
                    """, uuidBytes(DEMO_COURIER_ID), courierUsername);
            jdbc.update("""
                    INSERT INTO courier_capacity (courier_id, capacity_limit, committed_count, reserved_count,
                                                  version, updated_at)
                    VALUES (?, 3, 0, 0, 0, ?)
                    """, uuidBytes(DEMO_COURIER_ID), Timestamp.from(Instant.now(clock)));
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
