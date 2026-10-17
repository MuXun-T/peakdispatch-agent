package com.peakdispatch.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.config.DemoDataInitializer;
import com.peakdispatch.domain.order.DeliveryOrder;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderCommandApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired OrderApplicationService orders;
    @Autowired DispatchService dispatch;
    @Autowired JdbcTemplate jdbc;

    @Test
    void cancelEndpoint_replaysSameIdempotencyKeyWithoutRepeatingBusinessEffects() throws Exception {
        DeliveryOrder order = createOrder();
        String body = "{\"expectedVersion\":1,\"reason\":\"merchant_requested\"}";
        var merchant = SecurityMockMvcRequestPostProcessors.httpBasic("merchant-test", "merchant-test-password");

        MvcResult first = mockMvc.perform(post("/api/v1/orders/" + order.id() + "/cancel")
                        .with(merchant).header("Idempotency-Key", "cancel-api-" + order.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/orders/" + order.id() + "/cancel")
                        .with(merchant).header("Idempotency-Key", "cancel-api-" + order.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.cancelled'",
                order.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND action = 'ORDER_CANCELLED'",
                order.id().toString())).isEqualTo(1);
    }

    @Test
    void rescheduleEndpoint_replaysSameIdempotencyKeyWithoutRepeatingBusinessEffects() throws Exception {
        DeliveryOrder order = createOrder();
        dispatch.advanceCreatedOrders();
        String body = "{\"expectedVersion\":2,\"prepEta\":\"2035-01-01T10:10:00Z\","
                + "\"deadline\":\"2035-01-01T11:00:00Z\",\"reason\":\"merchant_delay\"}";
        var approver = SecurityMockMvcRequestPostProcessors.httpBasic("approver-test", "approver-test-password");

        MvcResult first = mockMvc.perform(post("/api/v1/orders/" + order.id() + "/reschedule")
                        .with(approver).header("Idempotency-Key", "reschedule-api-" + order.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_DISPATCH"))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/orders/" + order.id() + "/reschedule")
                        .with(approver).header("Idempotency-Key", "reschedule-api-" + order.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("SELECT COUNT(*) FROM delivery_event WHERE aggregate_id = ? AND event_type = 'order.rescheduled'",
                order.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND action = 'ORDER_RESCHEDULED'",
                order.id().toString())).isEqualTo(1);
    }

    private DeliveryOrder createOrder() {
        return orders.create(new OrderApplicationService.CreateOrderCommand(
                DemoDataInitializer.DEMO_MERCHANT_ID, DemoDataInitializer.DEMO_REGION_ID,
                30.250, 120.160, 30.251, 120.161,
                Instant.parse("2035-01-01T10:00:00Z"), Instant.parse("2035-01-01T11:00:00Z")));
    }

    private int count(String sql, Object parameter) {
        Integer value = jdbc.queryForObject(sql, Integer.class,
                parameter instanceof UUID uuid ? bytes(uuid) : parameter);
        return value == null ? 0 : value;
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
