package com.peakdispatch.api.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantPrepIntegrationTest {
    @Autowired MockMvc mockMvc;

    @Test
    void prepUpdate_withCurrentVersion_advancesOrderAndWritesEvent() throws Exception {
        String createBody = """
                {"merchantId":"00000000-0000-0000-0000-000000000101","regionId":"demo-region",
                 "pickup":{"lat":30.25,"lon":120.16},"dropoff":{"lat":30.27,"lon":120.18},
                 "prepEta":"2030-01-01T10:00:00Z","deadline":"2030-01-01T11:00:00Z"}
                """;
        String created = mockMvc.perform(post("/api/v1/orders")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant-test", "merchant-test-password"))
                        .header("Idempotency-Key", "prep-create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created)
                .path("data").path("orderId").asText();
        String eventId = UUID.randomUUID().toString();
        String prepBody = "{\"orderId\":\"" + orderId
                + "\",\"expectedVersion\":1,\"prepEta\":\"2030-01-01T10:15:00Z\","
                + "\"ready\":true,\"eventId\":\"" + eventId + "\"}";
        mockMvc.perform(post("/api/v1/merchants/00000000-0000-0000-0000-000000000101/prep")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant-test", "merchant-test-password"))
                        .header("Idempotency-Key", "prep-update-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(prepBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.preparationReady").value(true))
                .andExpect(jsonPath("$.data.prepEta").value("2030-01-01T10:15:00Z"));

        mockMvc.perform(post("/api/v1/merchants/00000000-0000-0000-0000-000000000101/prep")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant-test", "merchant-test-password"))
                        .header("Idempotency-Key", "prep-update-replay-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(prepBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        String changedPayload = prepBody.replace("10:15:00Z", "10:20:00Z");
        mockMvc.perform(post("/api/v1/merchants/00000000-0000-0000-0000-000000000101/prep")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant-test", "merchant-test-password"))
                        .header("Idempotency-Key", "prep-update-conflict-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(changedPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVENT_ID_REUSED"));
    }
}
