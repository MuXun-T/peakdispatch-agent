package com.peakdispatch.api.dispatch;

import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.api.system.RequestContext;
import com.peakdispatch.application.dispatch.DispatchPolicyProvider;
import com.peakdispatch.application.dispatch.DispatchPolicyService;
import com.peakdispatch.application.operations.OperationsService;
import com.peakdispatch.domain.dispatch.DispatchPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dispatch/policies")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class DispatchPolicyController {
    private final DispatchPolicyService policies;
    private final OperationsService operations;

    public DispatchPolicyController(DispatchPolicyService policies, OperationsService operations) {
        this.policies = policies;
        this.operations = operations;
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<PolicyView> active(@RequestParam @NotBlank String regionId, Authentication authentication) {
        requireRegion(authentication, regionId, "DISPATCH_POLICY_ACTIVE_READ", regionId);
        operations.recordAudit(authentication.getName(), "DISPATCH_POLICY_ACTIVE_READ", "REGION", regionId,
                "ALLOWED", "", RequestContext.traceId());
        return new ApiResponse<>(PolicyView.from(policies.active(regionId)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<List<PolicyView>> list(@RequestParam @NotBlank String regionId,
                                               Authentication authentication) {
        requireRegion(authentication, regionId, "DISPATCH_POLICY_HISTORY_READ", regionId);
        operations.recordAudit(authentication.getName(), "DISPATCH_POLICY_HISTORY_READ", "REGION", regionId,
                "ALLOWED", "", RequestContext.traceId());
        return new ApiResponse<>(policies.list(regionId).stream().map(PolicyView::from).toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<PolicyView> create(@Valid @RequestBody PolicyRequest request, Authentication authentication) {
        requireRegion(authentication, request.regionId(), "DISPATCH_POLICY_CREATE", request.regionId());
        DispatchPolicy policy = new DispatchPolicy(request.etaWeight(), request.loadWeight(), request.urgencyWeight(),
                request.maxPickupDistanceKm(), request.heartbeatTimeoutSeconds(), request.offerTtlSeconds(),
                request.maxRounds(), request.retryBackoffSeconds());
        return new ApiResponse<>(PolicyView.from(policies.create(request.regionId(), policy, authentication.getName())));
    }

    @PutMapping("/{policyId}")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<PolicyView> updateDraft(@PathVariable UUID policyId,
                                               @Valid @RequestBody PolicyUpdateRequest request,
                                               Authentication authentication) {
        DispatchPolicyProvider.PolicySelection current = policies.find(policyId);
        requireRegion(authentication, current.regionId(), "DISPATCH_POLICY_DRAFT_UPDATE", policyId.toString());
        DispatchPolicy policy = new DispatchPolicy(request.etaWeight(), request.loadWeight(), request.urgencyWeight(),
                request.maxPickupDistanceKm(), request.heartbeatTimeoutSeconds(), request.offerTtlSeconds(),
                request.maxRounds(), request.retryBackoffSeconds());
        return new ApiResponse<>(PolicyView.from(policies.updateDraft(policyId, policy, authentication.getName())));
    }

    @PostMapping("/{policyId}/activate")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<PolicyView> activate(@PathVariable UUID policyId, Authentication authentication) {
        DispatchPolicyProvider.PolicySelection current = policies.find(policyId);
        requireRegion(authentication, current.regionId(), "DISPATCH_POLICY_ACTIVATE", policyId.toString());
        return new ApiResponse<>(PolicyView.from(policies.activate(policyId, authentication.getName())));
    }

    @PostMapping("/{policyId}/validate")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<ValidationView> validate(@PathVariable UUID policyId, Authentication authentication) {
        DispatchPolicyProvider.PolicySelection current = policies.find(policyId);
        requireRegion(authentication, current.regionId(), "DISPATCH_POLICY_VALIDATE", policyId.toString());
        List<String> errors = validatePolicy(current.policy());
        operations.recordAudit(authentication.getName(), "DISPATCH_POLICY_VALIDATED", "DISPATCH_POLICY",
                policyId.toString(), errors.isEmpty() ? "ALLOWED" : "REJECTED",
                "version=" + current.version() + ";errorCount=" + errors.size(), RequestContext.traceId());
        return new ApiResponse<>(new ValidationView(policyId, current.version(), errors.isEmpty(), errors));
    }

    @PostMapping("/{policyId}/deactivate")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<PolicyView> deactivate(@PathVariable UUID policyId, Authentication authentication) {
        DispatchPolicyProvider.PolicySelection current = policies.find(policyId);
        requireRegion(authentication, current.regionId(), "DISPATCH_POLICY_DEACTIVATE", policyId.toString());
        return new ApiResponse<>(PolicyView.from(policies.deactivate(policyId, authentication.getName())));
    }

    @PostMapping("/regions/{regionId}/rollback")
    @PreAuthorize("hasAnyRole('OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<PolicyView> rollback(@PathVariable String regionId,
                                            @Valid @RequestBody RollbackRequest request,
                                            Authentication authentication) {
        requireRegion(authentication, regionId, "DISPATCH_POLICY_ROLLBACK", regionId);
        return new ApiResponse<>(PolicyView.from(policies.rollback(regionId, request.version(), authentication.getName())));
    }

    private void requireRegion(Authentication authentication, String regionId, String action, String targetId) {
        boolean allowed = authentication.getAuthorities().stream()
                .anyMatch(authority -> ("REGION_" + regionId).equals(authority.getAuthority()));
        if (!allowed) {
            operations.recordAudit(authentication.getName(), action, "REGION", targetId,
                    "DENIED", "region=" + regionId, RequestContext.traceId());
            throw new AccessDeniedException("dispatch policy is outside the authenticated user's region scope");
        }
    }

    private static List<String> validatePolicy(DispatchPolicy policy) {
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        if (policy.maxPickupDistanceKm() > 100) errors.add("maxPickupDistanceKm must not exceed 100");
        if (policy.heartbeatTimeoutSeconds() > 900) errors.add("heartbeatTimeoutSeconds must not exceed 900");
        if (policy.offerTtlSeconds() > 300) errors.add("offerTtlSeconds must not exceed 300");
        return List.copyOf(errors);
    }

    public record PolicyRequest(@NotBlank String regionId, double etaWeight, double loadWeight, double urgencyWeight,
                                double maxPickupDistanceKm, @Positive long heartbeatTimeoutSeconds,
                                @Positive long offerTtlSeconds, @Positive int maxRounds,
                                @NotNull long[] retryBackoffSeconds) {
    }

    public record PolicyUpdateRequest(double etaWeight, double loadWeight, double urgencyWeight,
                                      double maxPickupDistanceKm, @Positive long heartbeatTimeoutSeconds,
                                      @Positive long offerTtlSeconds, @Positive int maxRounds,
                                      @NotNull long[] retryBackoffSeconds) {
    }

    public record RollbackRequest(@Positive long version) {
    }

    public record ValidationView(UUID policyId, long version, boolean valid, List<String> errors) {
    }

    public record PolicyView(UUID policyId, long version, String regionId, DispatchPolicy policy,
                             String configJson, String status) {
        static PolicyView from(DispatchPolicyProvider.PolicySelection selection) {
            return new PolicyView(selection.id(), selection.version(), selection.regionId(),
                    selection.policy(), selection.configJson(), selection.status());
        }
    }
}
