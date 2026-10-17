package com.peakdispatch.api.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.application.courier.CourierPresenceService;
import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.config.DemoDataInitializer;
import com.peakdispatch.domain.order.DeliveryOrder;
import java.time.Instant;
import java.sql.Timestamp;
import java.nio.ByteBuffer;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DispatchLifecycleIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderApplicationService orders;
    @Autowired CourierPresenceService presence;
    @Autowired CourierRepository couriers;
    @Autowired DispatchService dispatch;
    @Autowired JdbcTemplate jdbc;

    @Test
    void courierPresence_dispatch_accept_pickup_and_delivery_keepCapacityLedgerConsistent() throws Exception {
        Instant now = Instant.now();
        DeliveryOrder created = orders.create(new OrderApplicationService.CreateOrderCommand(
                DemoDataInitializer.DEMO_MERCHANT_ID, DemoDataInitializer.DEMO_REGION_ID,
                30.250, 120.160, 30.251, 120.161,
                now.plusSeconds(300), now.plusSeconds(3600)));

        var online = presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        presence.heartbeat(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID,
                online.presenceGeneration(), 1, 30.250, 120.160);
        dispatch.advanceCreatedOrders();
        assertThat(orders.get(created.id()).status().name()).isEqualTo("WAITING_DISPATCH");

        DispatchService.DispatchDecision decision = dispatch.tryDispatch(created.id());
        assertThat(decision.outcome()).isEqualTo("OFFER_CREATED");
        JsonNode offerResponse = objectMapper.readTree(mockMvc.perform(get("/api/v1/couriers/"
                        + DemoDataInitializer.DEMO_COURIER_ID + "/offers")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("courier-test", "courier-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderId").value(created.id().toString()))
                .andReturn().getResponse().getContentAsString());
        JsonNode offer = offerResponse.path("data").get(0);
        String offerId = offer.path("offerId").asText();
        String token = offer.path("offerToken").asText();

        String accepted = mockMvc.perform(post("/api/v1/couriers/" + DemoDataInitializer.DEMO_COURIER_ID
                        + "/offers/" + offerId + "/accept")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("courier-test", "courier-test-password"))
                        .header("Idempotency-Key", "accept-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerToken\":\"" + token + "\",\"expectedVersion\":"
                                + offer.path("orderVersion").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        long acceptedVersion = objectMapper.readTree(accepted).path("data").path("version").asLong();
        assertThat(jdbc.queryForObject("SELECT token_ciphertext FROM dispatch_offer WHERE id = ?", String.class,
                uuidBytes(UUID.fromString(offerId)))).isNull();

        String pickup = mockMvc.perform(post("/api/v1/couriers/" + DemoDataInitializer.DEMO_COURIER_ID
                        + "/orders/" + created.id() + "/pickup")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("courier-test", "courier-test-password"))
                        .header("Idempotency-Key", "pickup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + acceptedVersion + ",\"eventId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKED_UP"))
                .andReturn().getResponse().getContentAsString();
        long pickupVersion = objectMapper.readTree(pickup).path("data").path("version").asLong();

        mockMvc.perform(post("/api/v1/couriers/" + DemoDataInitializer.DEMO_COURIER_ID
                        + "/orders/" + created.id() + "/deliver")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("courier-test", "courier-test-password"))
                        .header("Idempotency-Key", "deliver-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + pickupVersion + ",\"eventId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));

        assertThat(orders.get(created.id()).status().name()).isEqualTo("DELIVERED");
        assertThat(presenceOwnedCapacity()).isEqualTo(0);
    }

    @Test
    void courierCannotReadAnotherCouriersOffers() throws Exception {
        mockMvc.perform(get("/api/v1/couriers/00000000-0000-0000-0000-000000000999/offers")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("courier-test", "courier-test-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void tryDispatchBeforeNextDispatchTimeDoesNotCreateOffer() {
        Instant now = Instant.now();
        DeliveryOrder created = orders.create(new OrderApplicationService.CreateOrderCommand(
                DemoDataInitializer.DEMO_MERCHANT_ID, DemoDataInitializer.DEMO_REGION_ID,
                30.250, 120.160, 30.251, 120.161,
                now.plusSeconds(300), now.plusSeconds(3600)));
        var online = presence.setOnline(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID);
        presence.heartbeat(DemoDataInitializer.DEMO_COURIER_ID, DemoDataInitializer.DEMO_REGION_ID,
                online.presenceGeneration(), 1, 30.250, 120.160);
        dispatch.advanceCreatedOrders();
        jdbc.update("UPDATE delivery_order SET next_dispatch_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(30)), uuidBytes(created.id()));

        DispatchService.DispatchDecision decision = dispatch.tryDispatch(created.id());

        assertThat(decision.outcome()).isEqualTo("STALE_ORDER");
        assertThat(dispatch.activeOffers(DemoDataInitializer.DEMO_COURIER_ID)).isEmpty();
    }

    private int presenceOwnedCapacity() {
        var courier = couriers.find(DemoDataInitializer.DEMO_COURIER_ID).orElseThrow();
        return courier.committedCount() + courier.reservedCount();
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
