package com.peakdispatch.application.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {
    @Test
    void redact_removesPersonalAndCredentialValuesFromLogText() {
        String source = "姓名:张三 phone=13800138000 email=ops@example.com 地址:杭州市西湖区文三路88号 "
                + "Authorization: Bearer test-only.eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature123456 "
                + "trace=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature123456 "
                + "api_key=test-only-key password='test-only-value'";

        String redacted = SensitiveDataRedactor.redact(source);

        assertThat(redacted).contains("[NAME]", "[PHONE]", "[EMAIL]", "[ADDRESS]", "[AUTHORIZATION]", "[REDACTED]");
        assertThat(redacted).doesNotContain("张三", "13800138000", "ops@example.com", "文三路88号",
                "eyJhbGci", "signature123456", "test-only-key", "test-only-value");
    }

    @Test
    void redactJson_preservesJsonAndRemovesPlaintextOfferTokensAndPii() throws Exception {
        String source = "{\"offerToken\":\"raw-offer-token\",\"Authorization\":\"Bearer raw-auth-token\","
                + "\"address\":\"杭州市西湖区文三路88号\",\"email\":\"ops@example.com\",\"status\":\"OFFERED\"}";

        String redacted = SensitiveDataRedactor.redactJson(source);

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(redacted).get("status").asText())
                .isEqualTo("OFFERED");
        assertThat(redacted).doesNotContain("raw-offer-token", "raw-auth-token", "文三路88号", "ops@example.com");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void redact_keepsOrdinaryChineseOperationsPhrasesAndMasksUnlabeledStreetAddresses() {
        assertThat(SensitiveDataRedactor.redact("请比较区域高峰方案"))
                .isEqualTo("请比较区域高峰方案");
        assertThat(SensitiveDataRedactor.redact("杭州市西湖区文三路88号"))
                .isEqualTo("[ADDRESS]");
    }
}
