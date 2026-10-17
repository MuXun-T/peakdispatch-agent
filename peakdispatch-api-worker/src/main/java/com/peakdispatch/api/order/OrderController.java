package com.peakdispatch.api.order;

import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.domain.order.DeliveryOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/v1/orders")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class OrderController {
    private final OrderApplicationService orders;

    public OrderController(OrderApplicationService orders) {
        this.orders = orders;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MERCHANT', 'OPS_APPROVER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderView> create(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        boolean approver = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_OPS_APPROVER".equals(authority.getAuthority()));
        if (approver && !hasRegion(authentication, request.regionId())) {
            throw new AccessDeniedException("order is outside the authenticated user's region scope");
        }
        if (!approver && !orders.merchantOwnedBy(request.merchantId(), authentication.getName())) {
            throw new AccessDeniedException("merchant resource is not owned by the authenticated user");
        }
        DeliveryOrder order = orders.create(new OrderApplicationService.CreateOrderCommand(
                request.merchantId(), request.regionId(), request.pickup().lat(), request.pickup().lon(),
                request.dropoff().lat(), request.dropoff().lon(), request.prepEta(), request.deadline()));
        return new ApiResponse<>(OrderView.from(order));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COURIER', 'OPS_VIEWER', 'OPS_APPROVER')")
    public ApiResponse<OrderView> get(@PathVariable UUID id, Authentication authentication) {
        boolean readable = hasRole(authentication, "MERCHANT")
                ? orders.merchantOwnsOrder(id, authentication.getName())
                : hasRole(authentication, "COURIER")
                ? orders.courierWasAssigned(id, authentication.getName())
                : hasRole(authentication, "OPS_VIEWER") || hasRole(authentication, "OPS_APPROVER")
                ? orders.orderInRegions(id, authentication.getAuthorities().stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .filter(authority -> authority.startsWith("REGION_"))
                        .map(authority -> authority.substring("REGION_".length()))
                        .collect(java.util.stream.Collectors.toSet()))
                : false;
        if (!readable) {
            throw new AccessDeniedException("order is outside the authenticated user's resource scope");
        }
        return new ApiResponse<>(OrderView.from(orders.get(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('MERCHANT', 'OPS_APPROVER')")
    public ApiResponse<OrderView> cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request,
                                         Authentication authentication) {
        boolean approver = hasRole(authentication, "OPS_APPROVER");
        if (!approver && !orders.merchantOwnsOrder(id, authentication.getName())) {
            throw new AccessDeniedException("merchant resource is not owned by the authenticated user");
        }
        if (approver && !orderInAuthorizedRegion(id, authentication)) {
            throw new AccessDeniedException("order is outside the authenticated user's region scope");
        }
        return new ApiResponse<>(OrderView.from(orders.cancel(id, request.expectedVersion(), request.reason(),
                authentication.getName())));
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasRole('OPS_APPROVER')")
    public ApiResponse<OrderView> approveReschedule(@PathVariable UUID id,
                                                    @Valid @RequestBody RescheduleRequest request,
                                                    Authentication authentication) {
        if (!orderInAuthorizedRegion(id, authentication)) {
            throw new AccessDeniedException("order is outside the authenticated user's region scope");
        }
        return new ApiResponse<>(OrderView.from(orders.approveReschedule(id, request.expectedVersion(),
                request.prepEta(), request.deadline(), request.reason(), authentication.getName())));
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }

    private boolean orderInAuthorizedRegion(UUID orderId, Authentication authentication) {
        return orders.orderInRegions(orderId, regionIds(authentication));
    }

    private static boolean hasRegion(Authentication authentication, String regionId) {
        return regionIds(authentication).contains(regionId);
    }

    private static Set<String> regionIds(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("REGION_"))
                .map(authority -> authority.substring("REGION_".length()))
                .collect(java.util.stream.Collectors.toSet());
    }

    public record CreateOrderRequest(
            @NotNull UUID merchantId,
            @NotBlank String regionId,
            @NotNull @Valid Point pickup,
            @NotNull @Valid Point dropoff,
            @NotNull Instant prepEta,
            @NotNull Instant deadline) {
    }

    public record Point(
            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") double lat,
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") double lon) {
    }

    public record CancelRequest(long expectedVersion, @NotBlank String reason) {
    }

    public record RescheduleRequest(long expectedVersion, @NotNull Instant prepEta,
                                    @NotNull Instant deadline, @NotBlank String reason) {
    }

    public record OrderView(
            UUID orderId,
            UUID merchantId,
            String regionId,
            Point pickup,
            Point dropoff,
            Instant prepEta,
            Instant deadline,
            boolean preparationReady,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant asOf) {
        public static OrderView from(DeliveryOrder order) {
            return new OrderView(order.id(), order.merchantId(), order.regionId(),
                    new Point(order.pickupLat(), order.pickupLon()),
                    new Point(order.dropoffLat(), order.dropoffLon()), order.prepEta(), order.deadline(),
                    order.preparationReady(), order.status().name(), order.version(), order.createdAt(),
                    order.updatedAt(), order.updatedAt());
        }
    }
}
