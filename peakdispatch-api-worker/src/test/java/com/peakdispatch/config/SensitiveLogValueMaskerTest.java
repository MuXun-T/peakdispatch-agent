package com.peakdispatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveLogValueMaskerTest {
    @Test
    void masksPersonalDataAndCredentialsInFormattedLogMessages() {
        String message = "customer phone=13800138000 email=ops@example.com "
                + "address=杭州市西湖区文三路88号 name=张三 "
                + "Authorization=Bearer raw-authorization-secret token=test-only-token";

        Object masked = new SensitiveLogValueMasker().mask(null, message);

        assertThat(masked).asString().contains("[PHONE]", "[EMAIL]", "[ADDRESS]", "[NAME]", "[AUTHORIZATION]", "[REDACTED]")
                .doesNotContain("13800138000", "ops@example.com", "文三路88号", "张三",
                        "raw-authorization-secret", "test-only-token");
    }
}
