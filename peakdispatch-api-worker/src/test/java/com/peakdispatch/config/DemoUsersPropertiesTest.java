package com.peakdispatch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DemoUsersPropertiesTest {
    @Mock
    private PasswordEncoder encoder;

    @Test
    void generatedCourierPool_createsDistinctFixtureIdentities() {
        DemoUsersProperties properties = new DemoUsersProperties(baseUsers(),
                new DemoUsersProperties.CourierPool(5_000, "perf-courier-", "shared-password", Set.of("demo-region")));

        assertThat(properties.allIdentities()).hasSize(5_006);
        assertThat(properties.allIdentities()).extracting(DemoUsersProperties.Identity::username)
                .contains("perf-courier-00001", "perf-courier-05000")
                .doesNotHaveDuplicates();
    }

    @Test
    void generatedCourierPool_rejectsExistingUsernameCollision() {
        Map<String, DemoUsersProperties.Identity> users = baseUsers();
        users = new java.util.HashMap<>(users);
        users.put("collision", identity("perf-courier-00001", "collision-password", "COURIER"));

        Map<String, DemoUsersProperties.Identity> configuredUsers = users;
        assertThatThrownBy(() -> new DemoUsersProperties(configuredUsers,
                new DemoUsersProperties.CourierPool(1, "perf-courier-", "shared-password", Set.of("demo-region"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void demoUserDetailsService_encodesSharedCourierPasswordOnce() {
        when(encoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0));
        DemoUsersProperties properties = new DemoUsersProperties(baseUsers(),
                new DemoUsersProperties.CourierPool(5_000, "perf-courier-", "shared-password", Set.of("demo-region")));

        UserDetailsService users = new SecurityConfiguration().demoUserDetailsService(properties, encoder);

        assertThat(users.loadUserByUsername("perf-courier-05000").getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_COURIER", "REGION_demo-region");
        verify(encoder, times(1)).encode("shared-password");
    }

    private static Map<String, DemoUsersProperties.Identity> baseUsers() {
        return Map.of(
                "merchant", identity("merchant", "merchant-password", "MERCHANT"),
                "courier", identity("courier", "courier-password", "COURIER"),
                "ops-viewer", identity("ops-viewer", "viewer-password", "OPS_VIEWER"),
                "ops-approver", identity("ops-approver", "approver-password", "OPS_APPROVER"),
                "ops-approver-backup", identity("ops-approver-backup", "backup-password", "OPS_APPROVER"),
                "ops-admin", identity("ops-admin", "admin-password", "OPS_ADMIN"));
    }

    private static DemoUsersProperties.Identity identity(String username, String password, String role) {
        return new DemoUsersProperties.Identity(username, password, role, Set.of("demo-region"));
    }
}
