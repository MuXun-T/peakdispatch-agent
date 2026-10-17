package com.peakdispatch.application.dispatch;

import com.peakdispatch.application.courier.CourierRepository;
import com.peakdispatch.application.order.OrderApplicationService;
import com.peakdispatch.domain.courier.CourierPresence;
import com.peakdispatch.domain.order.DeliveryOrder;
import com.peakdispatch.domain.order.OrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only route and capacity preview. It never creates offers or reservations. */
@Service
public class BundleRoutePreviewService {
    public static final String RULE_VERSION = "demo-haversine-v1";
    private static final double ROAD_FACTOR = 1.3;
    private static final double SPEED_KM_PER_HOUR = 20.0;
    private static final Duration STOP_DURATION = Duration.ofMinutes(2);
    private static final int MAX_ORDERS = 3;

    private final OrderApplicationService orders;
    private final CourierRepository couriers;
    private final DispatchPolicyProvider policies;
    private final Clock clock;

    public BundleRoutePreviewService(OrderApplicationService orders, CourierRepository couriers,
                                     DispatchPolicyProvider policies, Clock clock) {
        this.orders = orders;
        this.couriers = couriers;
        this.policies = policies;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID courierId, List<UUID> orderIds) {
        validateRequest(courierId, orderIds);
        Instant asOf = Instant.now(clock);
        List<DeliveryOrder> candidates = orderIds.stream().map(orders::get).toList();
        CourierPresence courier = couriers.find(courierId)
                .orElseThrow(() -> new IllegalArgumentException("courier was not found"));
        List<String> failures = new ArrayList<>();

        String regionId = candidates.get(0).regionId();
        if (candidates.stream().anyMatch(order -> !regionId.equals(order.regionId()))) {
            failures.add("ORDERS_MUST_SHARE_REGION");
        }
        if (!regionId.equals(courier.regionId())) failures.add("COURIER_REGION_MISMATCH");
        if (candidates.stream().anyMatch(order -> order.status() != OrderStatus.WAITING_DISPATCH)) {
            failures.add("ORDER_NOT_WAITING_DISPATCH");
        }
        int committed = courier.committedCount();
        int reserved = courier.reservedCount();
        int projected = committed + reserved + candidates.size();
        if (projected > courier.capacityLimit()) failures.add("CAPACITY_EXCEEDED");
        if (!courier.isOnlineAt(asOf, policies.active(regionId).policy().heartbeatTimeoutSeconds())) {
            failures.add(courier.onlineState().name().equals("ONLINE")
                    ? "COURIER_HEARTBEAT_STALE" : "COURIER_OFFLINE");
        }
        if (courier.latitude() == null || courier.longitude() == null) failures.add("COURIER_LOCATION_MISSING");

        RouteEvaluation best = courier.latitude() == null || courier.longitude() == null
                ? new RouteEvaluation(List.of(), List.of(), 0, asOf, List.of(), 0, 0, "")
                : findBestRoute(candidates, courier, asOf);
        failures.addAll(best.deadlineFailures());
        List<OrderCandidate> candidateSet = candidates.stream()
                .map(order -> new OrderCandidate(order.id(), order.regionId(), order.status().name(), order.version(),
                        order.prepEta(), order.deadline(), 1))
                .toList();
        CapacityChange capacity = new CapacityChange(committed, reserved, candidates.size(), projected,
                courier.capacityLimit());
        return new Preview(RULE_VERSION, asOf, "mysql_primary", courierId, regionId, candidateSet,
                best.stops(), best.orderTimes(), best.distanceKm(), Duration.between(asOf, best.completedAt()),
                capacity, failures.stream().distinct().toList(), failures.isEmpty(), false,
                "只读路线预览，不创建 Offer、不改变订单状态，也不预占骑手容量。");
    }

    private static RouteEvaluation findBestRoute(List<DeliveryOrder> orders, CourierPresence courier, Instant asOf) {
        List<Stop> stops = new ArrayList<>();
        for (DeliveryOrder order : orders) {
            stops.add(new Stop(order, StopType.PICKUP, order.pickupLat(), order.pickupLon()));
            stops.add(new Stop(order, StopType.DROPOFF, order.dropoffLat(), order.dropoffLon()));
        }
        List<List<Stop>> permutations = new ArrayList<>();
        permute(stops, new ArrayList<>(), new HashSet<>(), permutations);
        return permutations.stream()
                .map(route -> evaluate(route, courier, asOf))
                .min(Comparator.comparingInt(RouteEvaluation::lateOrderCount)
                        .thenComparingLong(RouteEvaluation::totalLatenessSeconds)
                        .thenComparing(RouteEvaluation::completedAt)
                        .thenComparing(RouteEvaluation::stableOrder))
                .orElseThrow();
    }

    private static void permute(List<Stop> remaining, List<Stop> path, Set<UUID> pickedUp,
                                List<List<Stop>> result) {
        if (remaining.isEmpty()) {
            result.add(List.copyOf(path));
            return;
        }
        for (Stop stop : remaining) {
            if (stop.type() == StopType.DROPOFF && !pickedUp.contains(stop.order().id())) continue;
            List<Stop> nextRemaining = new ArrayList<>(remaining);
            nextRemaining.remove(stop);
            List<Stop> nextPath = new ArrayList<>(path);
            nextPath.add(stop);
            Set<UUID> nextPickedUp = new HashSet<>(pickedUp);
            if (stop.type() == StopType.PICKUP) nextPickedUp.add(stop.order().id());
            permute(nextRemaining, nextPath, nextPickedUp, result);
        }
    }

    private static RouteEvaluation evaluate(List<Stop> route, CourierPresence courier, Instant asOf) {
        Instant current = asOf;
        double latitude = courier.latitude() == null ? 0.0 : courier.latitude();
        double longitude = courier.longitude() == null ? 0.0 : courier.longitude();
        double distance = 0;
        Map<UUID, Instant> pickups = new HashMap<>();
        Map<UUID, Instant> deliveries = new HashMap<>();
        List<RouteStop> routeStops = new ArrayList<>();

        for (int index = 0; index < route.size(); index++) {
            Stop stop = route.get(index);
            double legDistance = haversine(latitude, longitude, stop.latitude(), stop.longitude()) * ROAD_FACTOR;
            distance += legDistance;
            Instant arrival = current.plusSeconds((long) Math.ceil(legDistance / SPEED_KM_PER_HOUR * 3600));
            Instant serviceStart = arrival;
            if (stop.type() == StopType.PICKUP && serviceStart.isBefore(stop.order().prepEta())) {
                serviceStart = stop.order().prepEta();
            }
            Instant serviceComplete = serviceStart.plus(STOP_DURATION);
            if (stop.type() == StopType.PICKUP) pickups.put(stop.order().id(), serviceComplete);
            else deliveries.put(stop.order().id(), serviceComplete);
            routeStops.add(new RouteStop(index + 1, stop.order().id(), stop.type().name(),
                    arrival, serviceStart, serviceComplete, legDistance));
            current = serviceComplete;
            latitude = stop.latitude();
            longitude = stop.longitude();
        }

        List<OrderTiming> timings = route.stream().map(Stop::order).distinct()
                .sorted(Comparator.comparing(order -> order.id().toString()))
                .map(order -> new OrderTiming(order.id(), pickups.get(order.id()), deliveries.get(order.id()),
                        order.prepEta(), order.deadline(), !pickups.get(order.id()).isBefore(order.prepEta()),
                        !deliveries.get(order.id()).isAfter(order.deadline())))
                .toList();
        List<String> failures = timings.stream().filter(timing -> !timing.deadlineMet())
                .map(timing -> "ORDER_DEADLINE_MISSED:" + timing.orderId()).toList();
        long lateness = timings.stream().mapToLong(timing -> Math.max(0,
                Duration.between(timing.deadline(), timing.estimatedDeliveredAt()).getSeconds())).sum();
        String stableOrder = route.stream().map(stop -> stop.order().id() + ":" + stop.type())
                .reduce((left, right) -> left + "," + right).orElse("");
        return new RouteEvaluation(List.copyOf(routeStops), timings, distance, current, failures,
                (int) timings.stream().filter(timing -> !timing.deadlineMet()).count(), lateness, stableOrder);
    }

    private static double haversine(double firstLat, double firstLon, double secondLat, double secondLon) {
        double lat1 = Math.toRadians(firstLat);
        double lat2 = Math.toRadians(secondLat);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(secondLon - firstLon);
        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static void validateRequest(UUID courierId, List<UUID> orderIds) {
        if (courierId == null || orderIds == null || orderIds.isEmpty() || orderIds.size() > MAX_ORDERS
                || orderIds.stream().anyMatch(id -> id == null)
                || new HashSet<>(orderIds).size() != orderIds.size()) {
            throw new IllegalArgumentException("courierId and one to three distinct orderIds are required");
        }
    }

    public record Preview(String ruleVersion, Instant asOf, String source, UUID courierId, String regionId,
                          List<OrderCandidate> candidateOrders, List<RouteStop> routeOrder,
                          List<OrderTiming> estimatedTimes, double estimatedDistanceKm,
                          Duration estimatedElapsed, CapacityChange capacity, List<String> failureReasons,
                          boolean executable, boolean mutatesDispatch, String limitation) {
    }

    public record OrderCandidate(UUID orderId, String regionId, String status, long version,
                                 Instant prepEta, Instant deadline, int capacityUnits) {
    }

    public record RouteStop(int sequence, UUID orderId, String action, Instant arrivalAt,
                            Instant serviceStartAt, Instant serviceCompleteAt, double legDistanceKm) {
    }

    public record OrderTiming(UUID orderId, Instant estimatedPickupAt, Instant estimatedDeliveredAt,
                              Instant prepEta, Instant deadline, boolean pickupWindowMet,
                              boolean deadlineMet) {
    }

    public record CapacityChange(int committedBefore, int reservedBefore, int addedUnits,
                                 int projectedUsed, int limit) {
    }

    private enum StopType { PICKUP, DROPOFF }

    private record Stop(DeliveryOrder order, StopType type, double latitude, double longitude) {
    }

    private record RouteEvaluation(List<RouteStop> stops, List<OrderTiming> orderTimes, double distanceKm,
                                   Instant completedAt, List<String> deadlineFailures, int lateOrderCount,
                                   long totalLatenessSeconds, String stableOrder) {
    }
}
