package com.peakdispatch.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DataEnvironmentIsolationGuard implements ApplicationRunner, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> active = Arrays.stream(environment.getActiveProfiles()).collect(Collectors.toSet());
        Set<String> defaults = Arrays.stream(environment.getDefaultProfiles()).collect(Collectors.toSet());
        String database = environment.getProperty("MYSQL_DATABASE");
        if (database == null) {
            String url = environment.getProperty("spring.datasource.url", "");
            int slash = url.lastIndexOf('/');
            int query = url.indexOf('?', slash + 1);
            database = slash < 0 ? "" : url.substring(slash + 1, query < 0 ? url.length() : query);
        }
        validate(environment.getProperty("peakdispatch.runtime.data-mode", "simulation"), active, defaults,
                environment.getProperty("peakdispatch.redis.environment", "demo"),
                database,
                environment.getProperty("MYSQL_PASSWORD", ""),
                environment.getProperty("peakdispatch.security.offer-encryption-key", ""));
    }

    static void validate(String mode, Set<String> activeProfiles, Set<String> defaultProfiles,
                         String redisEnvironment, String database, String databasePassword,
                         String offerEncryptionKey) {
        boolean production = "production".equalsIgnoreCase(mode) || "prod".equalsIgnoreCase(mode);
        boolean demoProfile = activeProfiles.contains("demo")
                || activeProfiles.isEmpty() && defaultProfiles.contains("demo");
        boolean productionProfile = activeProfiles.contains("prod") || activeProfiles.contains("production");
        String redis = redisEnvironment == null ? "" : redisEnvironment.toLowerCase();
        String db = database == null ? "" : database.toLowerCase();
        if (production) {
            if (demoProfile || !productionProfile || !redis.startsWith("prod")
                    || db.equals("peakdispatch") || db.contains("demo") || db.contains("sim") || db.contains("test")
                    || databasePassword == null || databasePassword.length() < 16
                    || offerEncryptionKey == null || offerEncryptionKey.length() < 32
                    || offerEncryptionKey.toLowerCase().contains("demo")
                    || offerEncryptionKey.toLowerCase().contains("change")) {
                throw new IllegalStateException("Production data must use a dedicated profile, database, Redis namespace, and real secrets");
            }
        } else if (productionProfile || redis.startsWith("prod") || db.contains("_prod")) {
            throw new IllegalStateException("Simulation data cannot share a production profile, database, or Redis namespace");
        }
    }
}
