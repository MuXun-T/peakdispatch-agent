package com.peakdispatch.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataEnvironmentIsolationGuardTest {
    @Test
    void committedEnvironmentExampleContainsOnlyPlaceholderCredentials() throws Exception {
        Path examplePath = Files.exists(Path.of(".env.example"))
                ? Path.of(".env.example") : Path.of("..", ".env.example");
        assertThat(Files.exists(examplePath)).isTrue();
        String example = Files.readString(examplePath, StandardCharsets.UTF_8);

        assertThat(example).contains("replace-with-local-db-password", "replace-with-local-root-password")
                .doesNotContain("AKIA", "ghp_", "sk-live-");
    }

    @Test
    void productionConfiguration_requiresDedicatedProfileStorageAndRealSecrets() {
        assertThatCode(() -> DataEnvironmentIsolationGuard.validate("production", Set.of("prod"), Set.of("demo"),
                "production-cn", "peakdispatch_prod", "db-secret-that-is-long-enough",
                "k0r8-prod-offer-encryption-key-32-bytes-minimum"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DataEnvironmentIsolationGuard.validate("production", Set.of("prod"), Set.of("demo"),
                "demo", "peakdispatch", "", "demo-only-change-this-offer-encryption-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void simulationConfiguration_rejectsProductionRedisOrDatabase() {
        assertThatCode(() -> DataEnvironmentIsolationGuard.validate("simulation", Set.of("demo"), Set.of(),
                "demo", "peakdispatch", "", "demo-only-key"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DataEnvironmentIsolationGuard.validate("simulation", Set.of("demo"), Set.of(),
                "production", "peakdispatch_prod", "", "demo-only-key"))
                .isInstanceOf(IllegalStateException.class);
    }
}
