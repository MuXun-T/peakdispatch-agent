package com.peakdispatch.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.adapter.agent.DeepSeekAgentIntentClassifier;
import com.peakdispatch.application.agent.AgentIntent;
import com.peakdispatch.application.agent.AgentIntentClassification;
import com.peakdispatch.application.agent.AgentPlan;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekAgentIntentClassifierTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void classify_sendsAuthenticatedStructuredRequestAndParsesIntent() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"{\\"intent\\":\\"ORDER_LOOKUP\\",\\"confidence\\":0.93,\\"missingFields\\":[]}"}}]}
                    """);
        });

        try {
            DeepSeekAgentIntentClassifier classifier = classifier(server);
            AgentIntentClassification result = classifier.classify("Show me the order timeline");

            assertThat(result.intent()).isEqualTo(AgentIntent.ORDER_LOOKUP);
            assertThat(result.confidence()).isEqualTo(0.93);
            assertThat(result.rationale()).isEqualTo("DEEPSEEK_STRUCTURED_CLASSIFICATION");
            assertThat(classifier.modelId()).isEqualTo("deepseek-flash");
            assertThat(classifier.promptVersion()).isEqualTo("deepseek-plan-v2");
            assertThat(requestPath.get()).isEqualTo("/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer test-secret");
            assertThat(requestBody.get().path("model").asText()).isEqualTo("deepseek-flash");
            assertThat(requestBody.get().path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(requestBody.get().path("messages").path(1).path("content").asText())
                    .isEqualTo("Show me the order timeline");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plan_sendsOnlySlotsAndParsesConstrainedToolPlan() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"{\\"intent\\":\\"REGION_METRICS\\",\\"confidence\\":0.91,\\"needsClarification\\":false,\\"clarificationQuestion\\":null,\\"toolName\\":\\"get_region_metrics\\",\\"arguments\\":{\\"regionId\\":\\"demo-region\\",\\"granularity\\":\\"hour\\"}}"}}]}
                    """);
        });
        try {
            AgentPlan result = classifier(server).plan("区域指标", Map.of("regionId", "demo-region"));

            assertThat(result.intent()).isEqualTo(AgentIntent.REGION_METRICS);
            assertThat(result.toolName()).isEqualTo("get_region_metrics");
            assertThat(result.arguments()).containsEntry("regionId", "demo-region");
            assertThat(result.needsClarification()).isFalse();
            assertThat(requestBody.get().path("response_format").path("type").asText()).isEqualTo("json_object");
            JsonNode content = mapper.readTree(requestBody.get().path("messages").path(1).path("content").asText());
            assertThat(content.path("suppliedSlots").path("regionId").asText()).isEqualTo("demo-region");
            assertThat(content.has("roles")).isFalse();
            assertThat(requestBody.get().path("messages").path(0).path("content").asText())
                    .contains("Never supply or infer identity").contains("request_cancel");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plan_recordsProviderTokenUsageWithoutChangingTheValidatedPlan() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PeakDispatchMetrics metrics = new PeakDispatchMetrics(registry);
        HttpServer server = server(exchange -> respond(exchange, 200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"REGION_METRICS\\",\\"confidence\\":0.91,\\"needsClarification\\":false,\\"clarificationQuestion\\":null,\\"toolName\\":\\"get_region_metrics\\",\\"arguments\\":{\\"regionId\\":\\"demo-region\\"}}"}}],
                 "usage":{"prompt_tokens":19,"completion_tokens":7,"total_tokens":26}}
                """));
        try {
            DeepSeekAgentIntentClassifier classifier = new DeepSeekAgentIntentClassifier(mapper,
                    baseUrl(server), "test-secret", "deepseek-flash", 1000, 3000, metrics);

            AgentPlan plan = classifier.plan("区域指标", Map.of("regionId", "demo-region"));

            assertThat(plan.toolName()).isEqualTo("get_region_metrics");
            assertThat(plan.tokenUsage().inputTokens()).isEqualTo(19);
            assertThat(plan.tokenUsage().outputTokens()).isEqualTo(7);
            assertThat(plan.tokenUsage().totalTokens()).isEqualTo(26);
            assertThat(registry.get("peakdispatch.model.tokens").tag("direction", "input").counter().count())
                    .isEqualTo(19.0);
            assertThat(registry.get("peakdispatch.model.tokens").tag("direction", "output").counter().count())
                    .isEqualTo(7.0);
            assertThat(registry.get("peakdispatch.model.http.responses").tag("status", "200").counter().count())
                    .isEqualTo(1.0);
        } finally {
            server.stop(0);
            registry.close();
        }
    }

    @Test
    void plan_rejectsMalformedOrIdentityBearingStructuredOutput() throws Exception {
        for (String planJson : new String[]{
                """
                {"intent":"REGION_METRICS","confidence":0.9}
                """,
                """
                {"intent":"REGION_METRICS","confidence":0.9,"needsClarification":false,
                 "clarificationQuestion":null,"toolName":"get_region_metrics",
                 "arguments":{"regionId":"demo-region"},"role":"OPS_ADMIN"}
                """
        }) {
            HttpServer server = server(exchange -> respond(exchange, 200,
                    mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                            Map.of("content", planJson)))))));
            try {
                assertThatThrownBy(() -> classifier(server).plan("区域指标", Map.of("regionId", "demo-region")))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void plan_whenProviderTimesOutFailsClosedWithoutExposingResponse() throws Exception {
        HttpServer server = server(exchange -> {
            try {
                Thread.sleep(500);
                respond(exchange, 200, "private provider response");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            DeepSeekAgentIntentClassifier classifier = new DeepSeekAgentIntentClassifier(mapper,
                    baseUrl(server), "test-secret", "deepseek-flash", 500, 75);
            long started = System.nanoTime();
            assertThatThrownBy(() -> classifier.plan("区域指标", Map.of("regionId", "demo-region")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DeepSeek API request failed")
                    .hasMessageNotContaining("private provider response")
                    .hasMessageNotContaining("test-secret");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classify_whenProviderRejectsRequest_doesNotExposeResponseBody() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 401, "private provider error detail"));
        try {
            DeepSeekAgentIntentClassifier classifier = classifier(server);

            assertThatThrownBy(() -> classifier.classify("query order"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DeepSeek API returned HTTP 401")
                    .hasMessageNotContaining("private provider error detail");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void constructor_whenApiKeyIsMissing_failsWithConfigurationHint() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "{}"));
        try {
            assertThatThrownBy(() -> new DeepSeekAgentIntentClassifier(mapper,
                    baseUrl(server), " ", "deepseek-flash", 1000, 1000))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DEEPSEEK_API_KEY is required when DeepSeek is selected");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private DeepSeekAgentIntentClassifier classifier(HttpServer server) {
        return new DeepSeekAgentIntentClassifier(mapper, baseUrl(server), "test-secret",
                "deepseek-flash", 1000, 3000);
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
