package com.peakdispatch.adapter.mysql;

import com.peakdispatch.application.dispatch.DispatchReadiness;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL-backed dispatch gate shared by separately deployed API and Worker roles. */
@Repository
@Profile("!test")
public class JdbcDispatchReadiness implements DispatchReadiness {
    private static final String GATE_NAME = "redis-projection";
    private final JdbcTemplate jdbc;

    public JdbcDispatchReadiness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean ready() {
        Boolean ready = jdbc.queryForObject("SELECT ready FROM dispatch_runtime_gate WHERE gate_name = ?",
                Boolean.class, GATE_NAME);
        return Boolean.TRUE.equals(ready);
    }

    @Override
    public void pause() {
        jdbc.update("UPDATE dispatch_runtime_gate SET ready = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP(3) WHERE gate_name = ?",
                GATE_NAME);
    }

    @Override
    public void resume() {
        jdbc.update("UPDATE dispatch_runtime_gate SET ready = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP(3) WHERE gate_name = ?",
                GATE_NAME);
    }
}
