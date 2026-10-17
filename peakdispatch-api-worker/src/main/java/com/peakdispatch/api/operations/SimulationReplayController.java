package com.peakdispatch.api.operations;

import com.peakdispatch.api.error.ApiException;
import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.api.system.RequestContext;
import com.peakdispatch.application.operations.SimulationReplayService;
import com.peakdispatch.application.operations.OperationsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ops/simulations/replays")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class SimulationReplayController {
    private final SimulationReplayService replays;
    private final OperationsService operations;

    public SimulationReplayController(SimulationReplayService replays, OperationsService operations) {
        this.replays = replays;
        this.operations = operations;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<SimulationReplayService.ReplayView> replay(@Valid @RequestBody ReplayRequest request,
                                                                  Authentication authentication) {
        requireRegion(authentication, request.regionId(), "OPS_REPLAY_CREATE", request.regionId());
        var result = replays.replay(new SimulationReplayService.ReplayRequest(request.regionId(), request.seed(),
                request.scenarioId(), request.orderCount(), request.courierCount(), request.policyVersion()),
                authentication.getName());
        return new ApiResponse<>(result);
    }

    @GetMapping("/{replayId}")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<SimulationReplayService.StoredReplay> get(@PathVariable UUID replayId,
                                                                  Authentication authentication) {
        var replay = replays.find(replayId).orElseThrow(() ->
                new ApiException("REPLAY_NOT_FOUND", "Replay was not found", HttpStatus.NOT_FOUND, false));
        requireRegion(authentication, replay.regionId(), "OPS_REPLAY_READ", replayId.toString());
        replays.auditRead(authentication.getName(), replay, "ALLOWED", RequestContext.traceId());
        return new ApiResponse<>(replay);
    }

    @PostMapping("/compare")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<SimulationReplayService.ReplayComparison> compare(@Valid @RequestBody CompareRequest request,
                                                                          Authentication authentication) {
        var first = replays.find(request.firstReplayId()).orElseThrow(() ->
                new ApiException("REPLAY_NOT_FOUND", "Replay was not found", HttpStatus.NOT_FOUND, false));
        var second = replays.find(request.secondReplayId()).orElseThrow(() ->
                new ApiException("REPLAY_NOT_FOUND", "Replay was not found", HttpStatus.NOT_FOUND, false));
        requireRegion(authentication, first.regionId(), "OPS_REPLAY_COMPARE", first.replayId().toString());
        requireRegion(authentication, second.regionId(), "OPS_REPLAY_COMPARE", second.replayId().toString());
        return new ApiResponse<>(replays.compare(first.replayId(), second.replayId(), authentication.getName(),
                RequestContext.traceId()));
    }

    private void requireRegion(Authentication authentication, String regionId, String action, String targetId) {
        boolean allowed = authentication.getAuthorities().stream()
                .anyMatch(authority -> ("REGION_" + regionId).equals(authority.getAuthority()));
        if (!allowed) {
            operations.recordAudit(authentication.getName(), action, "REGION", targetId,
                    "DENIED", "region=" + regionId, RequestContext.traceId());
            throw new AccessDeniedException("operation is outside the authenticated user's region scope");
        }
    }

    public record ReplayRequest(@NotBlank String regionId, long seed, @NotBlank String scenarioId,
                                @Min(1) @Max(500) int orderCount, @Min(1) @Max(200) int courierCount,
                                Long policyVersion) {
    }

    public record CompareRequest(UUID firstReplayId, UUID secondReplayId) {
        public CompareRequest {
            if (firstReplayId == null || secondReplayId == null) {
                throw new IllegalArgumentException("both replay ids are required");
            }
        }
    }
}
