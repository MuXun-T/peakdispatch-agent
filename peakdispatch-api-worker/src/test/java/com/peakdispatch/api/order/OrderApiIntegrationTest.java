package com.peakdispatch.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.config.DemoDataInitializer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createOrder_withSameIdempotencyKey_persistsOneOrderAndReplaysResponse() throws Exception {
        String key = "order-create-" + UUID.randomUUID();
        String body = """
                {"merchantId":"00000000-0000-0000-0000-000000000101","regionId":"demo-region",
                 "pickup":{"lat":30.25,"lon":120.16},"dropoff":{"lat":30.27,"lon":120.18},
                 "prepEta":"2030-01-01T10:00:00Z","deadline":"2030-01-01T11:00:00Z"}
                """;
        var credentials = SecurityMockMvcRequestPostProcessors.httpBasic("merchant-test", "merchant-test-password");

        String first = mockMvc.perform(post("/api/v1/orders").with(credentials)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/orders").with(credentials)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        UUID orderId = UUID.fromString(objectMapper.readTree(first).path("data").path("orderId").asText());
        Integer orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_order WHERE id = ?", Integer.class,
                uuidBytes(orderId));
        Integer slotCount = jdbc.queryForObject("SELECT COUNT(*) FROM order_dispatch_slot WHERE order_id = ?",
                Integer.class, uuidBytes(orderId));
        Integer outboxCount = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                Integer.class, uuidBytes(orderId));
        assertThat(orderCount).isEqualTo(1);
        assertThat(slotCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);

        mockMvc.perform(get("/api/v1/orders/" + orderId).with(credentials))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("CREATED"));

        mockMvc.perform(get("/api/v1/orders/" + orderId).with(SecurityMockMvcRequestPostProcessors
                        .httpBasic("courier-test", "courier-test-password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/" + orderId).with(SecurityMockMvcRequestPostProcessors
                        .httpBasic("ops-viewer-test", "ops-viewer-test-password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/" + orderId).with(SecurityMockMvcRequestPostProcessors
                        .httpBasic("approver-test", "approver-test-password")))
                .andExpect(status().isOk());
    }

    @Test
    void createOrder_withUnknownMerchant_returnsBusinessError() throws Exception {
        String body = """
                {"merchantId":"00000000-0000-0000-0000-000000000999","regionId":"demo-region",
                 "pickup":{"lat":30.25,"lon":120.16},"dropoff":{"lat":30.27,"lon":120.18},
                 "prepEta":"2030-01-01T10:00:00Z","deadline":"2030-01-01T11:00:00Z"}
                """;
        mockMvc.perform(post("/api/v1/orders").with(SecurityMockMvcRequestPostProcessors
                        .httpBasic("approver-test", "approver-test-password"))
                        .header("Idempotency-Key", "order-invalid-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MERCHANT_UNAVAILABLE"));
    }

    private static byte[] uuidBytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
