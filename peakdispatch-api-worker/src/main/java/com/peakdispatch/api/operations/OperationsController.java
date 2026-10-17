package com.peakdispatch.api.operations;

import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.api.system.RequestContext;
import com.peakdispatch.application.dispatch.BundleRoutePreviewService;
import com.peakdispatch.application.operations.OperationsRepository;
import com.peakdispatch.application.operations.OperationsService;
import com.peakdispatch.application.order.OrderNotFoundException;
import com.peakdispatch.domain.courier.CourierOnlineState;
import com.peakdispatch.domain.order.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ops")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class OperationsController {
    private final OperationsService operations;
    private final BundleRoutePreviewService bundlePreviews;

    public OperationsController(OperationsService operations, BundleRoutePreviewService bundlePreviews) {
        this.operations = operations;
        this.bundlePreviews = bundlePreviews;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<OperationsRepository.DashboardSnapshot> dashboard(
            @RequestParam String regionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Authentication authentication) {
        requireRegion(authentication, regionId, "OPS_DASHBOARD_READ", regionId);
        return new ApiResponse<>(operations.dashboard(regionId, from, to, authentication.getName(),
                RequestContext.traceId()));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<OperationsRepository.OrderPage> orders(
            @RequestParam String regionId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        requireRegion(authentication, regionId, "OPS_ORDERS_READ", regionId);
        return new ApiResponse<>(operations.searchOrders(regionId, status, orderId, createdFrom, createdTo,
                page, size, authentication.getName(), RequestContext.traceId()));
    }

    @GetMapping("/couriers")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<OperationsRepository.CourierPage> couriers(
            @RequestParam String regionId,
            @RequestParam(required = false) CourierOnlineState onlineState,
            @RequestParam(required = false) UUID courierId,
            @RequestParam(required = false) Long presenceGeneration,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        requireRegion(authentication, regionId, "OPS_COURIERS_READ", regionId);
        return new ApiResponse<>(operations.searchCouriers(regionId, onlineState, courierId,
                presenceGeneration, page, size, authentication.getName(), RequestContext.traceId()));
    }

    @PostMapping("/dispatch/bundle-previews")
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<BundleRoutePreviewService.Preview> previewBundle(
            @Valid @RequestBody BundlePreviewRequest request, Authentication authentication) {
        for (UUID orderId : request.orderIds()) {
            String region = operations.orderRegion(orderId);
            if (region == null) throw new OrderNotFoundException(orderId);
            requireRegion(authentication, region, "OPS_BUNDLE_ROUTE_PREVIEW", orderId.toString());
        }
        String courierRegion = operations.courierRegion(request.courierId());
        if (courierRegion == null) throw new IllegalArgumentException("courier was not found");
        requireRegion(authentication, courierRegion, "OPS_BUNDLE_ROUTE_PREVIEW", request.courierId().toString());
        BundleRoutePreviewService.Preview preview = bundlePreviews.preview(request.courierId(), request.orderIds());
        operations.recordAudit(authentication.getName(), "OPS_BUNDLE_ROUTE_PREVIEW", "REGION",
                preview.regionId(), "ALLOWED", "orderCount=" + preview.candidateOrders().size()
                        + ";executable=" + preview.executable(), RequestContext.traceId());
        return new ApiResponse<>(preview);
    }

    @GetMapping({"/orders/{orderId}/timeline", "/dispatches/{orderId}/timeline"})
    @PreAuthorize("hasAnyRole('OPS_VIEWER', 'OPS_APPROVER', 'OPS_ADMIN')")
    public ApiResponse<List<OperationsRepository.TimelineEntry>> timeline(@PathVariable UUID orderId,
                                                                           Authentication authentication) {
        String regionId = operations.orderRegion(orderId);
        if (regionId == null) throw new OrderNotFoundException(orderId);
        requireRegion(authentication, regionId, "OPS_ORDER_TIMELINE_READ", orderId.toString());
        return new ApiResponse<>(operations.orderTimeline(orderId, authentication.getName(), RequestContext.traceId()));
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

    public record BundlePreviewRequest(@NotNull UUID courierId,
                                       @NotEmpty @Size(max = 3) List<@NotNull UUID> orderIds) {
    }
}
