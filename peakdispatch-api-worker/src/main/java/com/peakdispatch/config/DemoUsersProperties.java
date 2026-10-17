package com.peakdispatch.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peakdispatch.demo")
public record DemoUsersProperties(Map<String, Identity> users, CourierPool courierPool) {
    public DemoUsersProperties {
        users = users == null ? Map.of() : Map.copyOf(users);
        courierPool = courierPool == null ? CourierPool.disabled() : courierPool;
        if (users.isEmpty()) {
            throw new IllegalArgumentException("at least one demo identity must be configured");
        }
        Set<String> usernames = users.values().stream().map(Identity::username).collect(Collectors.toSet());
        long approvers = users.values().stream().filter(user -> "OPS_APPROVER".equals(user.role())).count();
        if (usernames.size() != users.size() || approvers < 2) {
            throw new IllegalArgumentException("demo identities need unique usernames and two OPS_APPROVER accounts");
        }
        for (int index = 1; index <= courierPool.count(); index++) {
            if (!usernames.add(courierPool.username(index))) {
                throw new IllegalArgumentException("generated courier usernames must be unique");
            }
        }
    }

    public List<Identity> allIdentities() {
        List<Identity> identities = new ArrayList<>(users.values());
        for (int index = 1; index <= courierPool.count(); index++) {
            identities.add(new Identity(courierPool.username(index), courierPool.password(), "COURIER",
                    courierPool.regionScopes()));
        }
        return List.copyOf(identities);
    }

    public record CourierPool(int count, String usernamePrefix, String password, Set<String> regionScopes) {
        private static final int MAX_COUNT = 10_000;

        public CourierPool {
            usernamePrefix = usernamePrefix == null ? "" : usernamePrefix;
            password = password == null ? "" : password;
            regionScopes = regionScopes == null || regionScopes.isEmpty()
                    ? Set.of("demo-region") : Set.copyOf(regionScopes);
            if (count < 0 || count > MAX_COUNT || (count > 0
                    && (usernamePrefix.isBlank() || password.isBlank()
                    || regionScopes.stream().anyMatch(scope -> scope == null || scope.isBlank())))) {
                throw new IllegalArgumentException("generated courier account pool is invalid");
            }
        }

        static CourierPool disabled() {
            return new CourierPool(0, "", "", Set.of("demo-region"));
        }

        private String username(int oneBasedIndex) {
            return usernamePrefix + String.format(Locale.ROOT, "%05d", oneBasedIndex);
        }
    }

    public record Identity(String username, String password, String role, Set<String> regionScopes) {
        public Identity {
            regionScopes = regionScopes == null ? Set.of("demo-region") : Set.copyOf(regionScopes);
            if (username == null || username.isBlank() || password == null || password.isBlank()
                    || role == null || !role.matches("MERCHANT|COURIER|OPS_VIEWER|OPS_APPROVER|OPS_ADMIN")
                    || regionScopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
                throw new IllegalArgumentException("invalid demo identity configuration");
            }
        }
    }
}
