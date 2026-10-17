package com.peakdispatch.api.courier;

import com.peakdispatch.api.system.ApiResponse;
import com.peakdispatch.application.courier.CourierPresenceService;
import com.peakdispatch.application.dispatch.DispatchRepository;
import com.peakdispatch.application.dispatch.DispatchService;
import com.peakdispatch.application.observability.PeakDispatchMetrics;
import com.peakdispatch.domain.courier.CourierPresence;
import com.peakdispatch.domain.order.DeliveryOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/couriers/{courierId}")
@ConditionalOnExpression("'${peakdispatch.runtime.role:ALL}' != 'WORKER'")
public class CourierController {
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 90;
    private final CourierPresenceService presence;
    private final DispatchService dispatch;
    private final PeakDispatchMetrics metrics;

    public CourierController(CourierPresenceService presence, DispatchService dispatch) {
        this(presence, dispatch, null);
    }

    @Autowired
    public CourierController(CourierPresenceService presence, DispatchService dispatch, PeakDispatchMetrics metrics) {
        this.presence = presence;
        this.dispatch = dispatch;
        this.metrics = metrics;
    }

    @PutMapping("/presence")
    @PreAuthorize("hasAnyRole('COURIER', 'OPS_APPROVER')")
    public ApiResponse<PresenceView> setPresence(@PathVariable UUID courierId,
                                                @Valid @RequestBody PresenceRequest request,
                                                Authentication authentication) {
        authorize(courierId, authentication);
        CourierPresence result = request.online()
                ? presence.setOnline(courierId, request.regionId())
                : presence.setOffline(courierId);
        return new ApiResponse<>(PresenceView.from(result));
    }

    @PostMapping("/heartbeat")
    @PreAuthorize("hasAnyRole('COURIER', 'OPS_APPROVER')")
    public ApiResponse<HeartbeatView> heartbeat(@PathVariable UUID courierId,
                                                @Valid @RequestBody HeartbeatRequest request,
                                                Authentication authentication) {
        authorize(courierId, authentication);
        CourierPresence result = presence.heartbeat(courierId, request.regionId(), request.presenceGeneration(),
                request.sequence(), request.lat(), request.lon());
        Instant acceptedAt = result.lastHeartbeatAt();
        return new ApiResponse<>(new HeartbeatView(true, acceptedAt,
                acceptedAt.plusSeconds(HEARTBEAT_TIMEOUT_SECONDS), result.presenceGeneration(), result.sequence()));
    }

    @GetMapping("/offers")
    @PreAuthorize("hasAnyRole('COURIER', 'OPS_APPROVER')")
    public ApiResponse<List<DispatchService.CourierOffer>> activeOffers(@PathVariable UUID courierId,
                                                                        Authentication authentication) {
        authorize(courierId, authentication);
        return new ApiResponse<>(dispatch.activeOffers(courierId));
    }

    @PostMapping("/offers/{offerId}/accept")
    @PreAuthorize("hasAnyRole('COURIER', 'OPS_APPROVER')")
    public ApiResponse<OrderCommandView> accept(@PathVariable UUID courierId, @PathVariable UUID offerId,
                                               @Valid @RequestBody OfferAcceptRequest request,
                                               Authentication authentication) {
        authorize(courierId, authentication);
        DispatchRepository.OfferCommandResult result = dispatch.accept(courierId, offerId,
                request.offerToken(), request.expectedVersion());
        if (metrics != null) metrics.offer("accepted");
        return new ApiResponse<>(OrderCommandView.from(result.order(), result.offer().id()));
    }

    @PostMapping("/offers/{offerId}/reject")
    @PreAuthorize("hasAnyRole('COURIER', 'OPS_APPROVER')")
    public ApiResponse<OrderCommandView> reject(@PathVariable UUID courierId, @PathVariable UUID offerId,
                                                @Valid @RequestBody OfferRejectRequest request,
                                                Authentication authentication) {
        authorize(courierId, authentication);
        DispatchRepository.OfferCommandResult result = dispatch.reject(courierId, offerId,
                request.offerToken(), request.reasonCode());
        if (metrics != null) metrics.offer("declined");
        return new ApiResponse<>(OrderCommandView.from(result.order(), result.offer().id()));
    }

    @PostMapping("/orders/{orderId}/pickup")
    @PreAuthorize("hasAnyRole('COURIER', 'OPS_APPROVER')")
    public ApiResponse<OrderCommandView> pickup(@PathVariable UUID courierId, @PathVariable UUID orderId,
                                                @Valid @RequestBody OrderMilestoneRequest request,
                                                Authentication authentication) {
        authorize(courierId, authentication);
        return new ApiResponse<>(OrderCommandView.from(dispatch.pickup(courierId, orderId,
                request.expectedVersion(), request.eventId()), null));
    }

    @PostMapping("/orders/{orderId}/deliver")
    @PreAuthorize("hasAnyRole('COURIER', 'OPS_APPROVER')")
    public ApiResponse<OrderCommandView> deliver(@PathVariable UUID courierId, @PathVariable UUID orderId,
                                                 @Valid @RequestBody OrderMilestoneRequest request,
                                                 Authentication authentication) {
        authorize(courierId, authentication);
        return new ApiResponse<>(OrderCommandView.from(dispatch.deliver(courierId, orderId,
                request.expectedVersion(), request.eventId()), null));
    }

    private void authorize(UUID courierId, Authentication authentication) {
        boolean approver = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_OPS_APPROVER".equals(authority.getAuthority()));
        if (!approver && !presence.ownedBy(courierId, authentication.getName())) {
            throw new AccessDeniedException("courier resource is not owned by the authenticated user");
        }
    }

    public record PresenceRequest(boolean online, String regionId) {
        public PresenceRequest {
            if (online && (regionId == null || regionId.isBlank())) {
                throw new IllegalArgumentException("regionId is required when going online");
            }
        }
    }

    public record HeartbeatRequest(@NotBlank String regionId, @Positive long presenceGeneration,
                                   @Positive long sequence,
                                   @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
                                   @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
                                   Instant occurredAt) {
    }

    public record OfferAcceptRequest(@NotBlank String offerToken, @Positive long expectedVersion) {
    }

    public record OfferRejectRequest(@NotBlank String offerToken, @NotBlank String reasonCode) {
    }

    public record OrderMilestoneRequest(@Positive long expectedVersion, @NotNull UUID eventId) {
    }

    public record PresenceView(boolean online, String regionId, long presenceGeneration,
                               long nextSequence, Instant heartbeatExpiresAt) {
        static PresenceView from(CourierPresence presence) {
            Instant expiresAt = presence.lastHeartbeatAt() == null ? null
                    : presence.lastHeartbeatAt().plusSeconds(HEARTBEAT_TIMEOUT_SECONDS);
            return new PresenceView(presence.onlineState().name().equals("ONLINE"), presence.regionId(),
                    presence.presenceGeneration(), presence.sequence() + 1, expiresAt);
        }
    }

    public record HeartbeatView(boolean accepted, Instant acceptedAt, Instant heartbeatExpiresAt,
                                long presenceGeneration, long sequence) {
    }

    public record OrderCommandView(UUID orderId, UUID offerId, String status, long version,
                                   Instant updatedAt, Instant asOf) {
        static OrderCommandView from(DeliveryOrder order, UUID offerId) {
            return new OrderCommandView(order.id(), offerId, order.status().name(), order.version(),
                    order.updatedAt(), order.updatedAt());
        }
    }
}
