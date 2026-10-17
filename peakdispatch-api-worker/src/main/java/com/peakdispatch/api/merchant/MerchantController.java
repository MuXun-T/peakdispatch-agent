package com.peakdispatch.api.merchant;

import com.peakdispatch.api.order.OrderController.OrderView;
import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.application.order.OrderApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class MerchantController {
    private final OrderApplicationService orders;

    public MerchantController(OrderApplicationService orders) {
        this.orders = orders;
    }

    @PostMapping("/prep")
    @PreAuthorize("hasAnyRole('MERCHANT', 'OPS_APPROVER')")
    public ApiResponse<OrderView> updatePreparation(@PathVariable UUID merchantId,
                                                    @Valid @RequestBody PrepUpdateRequest request,
                                                    org.springframework.security.core.Authentication authentication) {
        boolean approver = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_OPS_APPROVER".equals(authority.getAuthority()));
        if (!approver && !orders.merchantOwnedBy(merchantId, authentication.getName())) {
            throw new org.springframework.security.access.AccessDeniedException("merchant resource is not owned by the authenticated user");
        }
        return new ApiResponse<>(OrderView.from(orders.updatePreparation(merchantId, request.orderId(),
                request.expectedVersion(), request.prepEta(), request.ready(), request.eventId(),
                authentication.getName())));
    }

    public record PrepUpdateRequest(@NotNull UUID orderId, long expectedVersion, @NotNull Instant prepEta,
                                    boolean ready, @NotNull UUID eventId) {
    }
}
