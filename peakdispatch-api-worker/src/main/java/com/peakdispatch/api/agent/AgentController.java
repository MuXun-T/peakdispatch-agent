package com.peakdispatch.api.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.api.system.RequestContext;
import com.peakdispatch.application.agent.AgentActionRecord;
import com.peakdispatch.application.agent.AgentConversationTurn;
import com.peakdispatch.application.agent.AgentConversationService;
import com.peakdispatch.application.agent.AgentIntent;
import com.peakdispatch.application.agent.AgentPlan;
import com.peakdispatch.application.agent.AgentPrincipal;
import com.peakdispatch.application.agent.AgentRunEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/ops")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class AgentController {
    private final AgentConversationService agents;
    private final ObjectMapper mapper;
    private final long pollDelayNanos;
    private final long heartbeatNanos;
    private final long maxStreamNanos;
    private static final MediaType SSE_UTF8 = new MediaType("text", "event-stream", StandardCharsets.UTF_8);

    public AgentController(AgentConversationService agents, ObjectMapper mapper,
                           @org.springframework.beans.factory.annotation.Value("${peakdispatch.agent.sse-poll-delay-ms:250}")
                           long pollDelayMs,
                           @org.springframework.beans.factory.annotation.Value("${peakdispatch.agent.sse-heartbeat-ms:15000}")
                           long heartbeatMs,
                           @org.springframework.beans.factory.annotation.Value("${peakdispatch.agent.sse-max-duration-ms:60000}")
                           long maxStreamMs) {
        this.agents = agents;
        this.mapper = mapper;
        this.pollDelayNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(10, pollDelayMs));
        this.heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1000, heartbeatMs));
        this.maxStreamNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1000, maxStreamMs));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ResponseEntity<StreamingResponseBody> chat(@Valid @RequestBody ChatRequest request,
                                                       @RequestHeader("Idempotency-Key") String requestKey,
                                                       Authentication authentication) {
        AgentPrincipal principal = principal(authentication);
        AgentConversationService.RunSummary run = agents.startChat(new AgentConversationService.ChatCommand(
                request.message(), request.sessionId(), request.regionId(), request.orderId(),
                request.expectedVersion(), request.reasonCode(), request.prepEta(), request.deadline(),
                request.from(), request.to(), request.category(), request.seed()), requestKey, principal, RequestContext.traceId());
        return ResponseEntity.ok().contentType(SSE_UTF8)
                .header("X-Agent-Run-Id", run.runId().toString())
                .header("X-Agent-Session-Id", run.sessionId().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream(run.runId(), 0, principal));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<AgentConversationService.RunSummary> summary(@PathVariable UUID runId,
                                                                     Authentication authentication) {
        return new ApiResponse<>(agents.summary(runId, principal(authentication)));
    }

    @GetMapping("/sessions/{sessionId}/turns")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<List<AgentConversationTurn>> conversation(
            @PathVariable UUID sessionId, Authentication authentication) {
        return new ApiResponse<>(agents.conversation(sessionId, principal(authentication)));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ResponseEntity<StreamingResponseBody> events(@PathVariable UUID runId,
                                                         @RequestHeader(value = "Last-Event-ID", required = false)
                                                         String lastEventId,
                                                         Authentication authentication) {
        long cursor = parseCursor(runId, lastEventId);
        AgentPrincipal principal = principal(authentication);
        agents.events(runId, cursor, principal);
        return ResponseEntity.ok().contentType(SSE_UTF8)
                .cacheControl(CacheControl.noCache()).header("X-Accel-Buffering", "no")
                .body(stream(runId, cursor, principal));
    }

    @PostMapping({"/actions/{actionId}/approve", "/actions/{actionId}/confirm"})
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<AgentActionRecord> approve(@PathVariable UUID actionId, Authentication authentication) {
        return new ApiResponse<>(agents.approve(actionId, principal(authentication), RequestContext.traceId()));
    }

    @PostMapping("/orders/{orderId}/reassignment-requests")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<AgentConversationService.RunSummary> requestReassignment(
            @PathVariable UUID orderId, @Valid @RequestBody ReassignmentRequest request,
            @RequestHeader("Idempotency-Key") String requestKey, Authentication authentication) {
        AgentConversationService.ChatCommand command = new AgentConversationService.ChatCommand(
                "重新派单", null, null, orderId, request.expectedVersion(), request.reasonCode(),
                null, null, null, null);
        AgentPlan plan = new AgentPlan(AgentIntent.REASSIGN_ORDER, 1.0, false, null,
                "request_reassignment", Map.of("orderId", orderId.toString(),
                        "expectedVersion", request.expectedVersion(), "reasonCode", request.reasonCode()));
        AgentConversationService.RunSummary run = agents.chatWithServerPlan(command, requestKey,
                principal(authentication), RequestContext.traceId(), plan);
        return new ApiResponse<>(run);
    }

    @PostMapping("/actions/{actionId}/reject")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<AgentActionRecord> reject(@PathVariable UUID actionId,
                                                  @RequestBody(required = false) RejectRequest request,
                                                  Authentication authentication) {
        return new ApiResponse<>(agents.reject(actionId, request == null ? null : request.reasonCode(),
                principal(authentication), RequestContext.traceId()));
    }

    private StreamingResponseBody stream(UUID runId, long initialCursor, AgentPrincipal principal) {
        return output -> {
            long cursor = initialCursor;
            long deadline = System.nanoTime() + maxStreamNanos;
            long lastHeartbeat = System.nanoTime();
            while (System.nanoTime() < deadline) {
                List<AgentRunEvent> events = agents.events(runId, cursor, principal);
                for (AgentRunEvent event : events) {
                    String data;
                    try {
                        data = mapper.writeValueAsString(event.payload());
                    } catch (IOException exception) {
                        throw new IllegalStateException("Agent event could not be serialized", exception);
                    }
                    String frame = "id: " + runId + ":" + event.sequence() + "\nevent: " + event.eventType()
                            + "\ndata: " + data + "\n\n";
                    output.write(frame.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    cursor = event.sequence();
                }
                if (agents.summary(runId, principal).status().terminal()) return;

                long now = System.nanoTime();
                if (now - lastHeartbeat >= heartbeatNanos) {
                    output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    lastHeartbeat = now;
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(pollDelayNanos, Math.max(0, deadline - now)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        };
    }

    private static long parseCursor(UUID runId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return 0;
        String value = lastEventId.trim();
        int separator = value.lastIndexOf(':');
        if (separator >= 0) {
            if (!runId.toString().equals(value.substring(0, separator))) {
                throw new IllegalArgumentException("Last-Event-ID belongs to another run");
            }
            value = value.substring(separator + 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must contain a run sequence");
        }
    }

    private static AgentPrincipal principal(Authentication authentication) {
        Set<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith("ROLE_")).map(value -> value.substring(5))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> regions = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith("REGION_")).map(value -> value.substring(7))
                .collect(Collectors.toUnmodifiableSet());
        return new AgentPrincipal(authentication.getName(), roles, regions);
    }

    public record ChatRequest(@NotBlank @Size(max = 4000) String message, UUID sessionId, String regionId,
                             UUID orderId, Long expectedVersion, String reasonCode,
                             Instant prepEta, Instant deadline, Instant from, Instant to, String category, Long seed) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("unsupported chat field: " + field);
        }
    }

    public record RejectRequest(String reasonCode) {
    }

    public record ReassignmentRequest(@Min(1) long expectedVersion,
                                      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String reasonCode) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("unsupported reassignment field: " + field);
        }
    }
}
