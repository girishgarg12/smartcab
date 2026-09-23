package com.smartcab.service;

import com.smartcab.dto.*;
import com.smartcab.entity.*;
import com.smartcab.repository.CabRepository;
import com.smartcab.repository.RouteRepository;
import com.smartcab.repository.RouteStopRepository;
import com.smartcab.routing.optimizer.OptimizedRoute;
import com.smartcab.routing.optimizer.OptimizedStop;
import com.smartcab.routing.optimizer.RouteOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service managing dynamic local replanning of cab routes when an assigned employee cancels their booking.
 * <p>
 * Ensures that:
 * <ul>
 *   <li>Only the cab containing the cancelling employee is recalculated</li>
 *   <li>Unrelated routes and vehicles remain untouched</li>
 *   <li>All hard constraints are revalidated</li>
 *   <li>Database modifications are atomic under {@link Transactional}</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouteReplanningService {

    public static final int DEFAULT_MAX_RIDE_TIME_MINUTES = 60;
    public static final double DEFAULT_AVERAGE_SPEED_KMH = 30.0;

    private final RouteStopRepository routeStopRepository;
    private final RouteRepository routeRepository;
    private final CabRepository cabRepository;
    private final RouteOptimizer routeOptimizer;

    /**
     * Replans only the route containing the cancelled booking.
     *
     * @param cancelledBooking The cancelled booking
     * @return Optional containing the updated route response, or empty if the route was cancelled (no remaining stops) or booking was unassigned
     */
    @Transactional
    public Optional<RouteResponse> replanRouteOnCancellation(Booking cancelledBooking) {
        if (cancelledBooking == null || cancelledBooking.getId() == null) {
            return Optional.empty();
        }

        // 1. Find the RouteStop for this booking
        Optional<RouteStop> stopOpt = routeStopRepository.findByBookingId(cancelledBooking.getId());
        if (stopOpt.isEmpty()) {
            log.info("Cancelled booking {} was not assigned to any route; no route replanning needed", cancelledBooking.getId());
            return Optional.empty();
        }

        RouteStop cancelledStop = stopOpt.get();
        Route route = cancelledStop.getRoute();
        Cab cab = route.getCab();

        // 2. Fetch all current stops for this cab route
        List<RouteStop> allStops = routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId());

        // 3. Filter remaining bookings
        List<Booking> remainingBookings = allStops.stream()
                .filter(s -> !s.getBooking().getId().equals(cancelledBooking.getId()))
                .map(RouteStop::getBooking)
                .toList();

        // Delete all old stops for this route
        routeStopRepository.deleteAll(allStops);

        // 4. If no employees remain on this route, cancel the route and release the cab
        if (remainingBookings.isEmpty()) {
            log.info("No passengers remaining on route {}. Marking route CANCELLED and releasing cab {}",
                    route.getId(), cab.getVehicleNumber());
            route.setStatus(RouteStatus.CANCELLED);
            routeRepository.save(route);

            cab.setStatus(CabStatus.AVAILABLE);
            cabRepository.save(cab);

            return Optional.empty();
        }

        // 5. Recalculate route for remaining employees on this cab only
        log.info("Recalculating route {} for {} remaining passenger(s) in cab {}",
                route.getId(), remainingBookings.size(), cab.getVehicleNumber());

        Optional<OptimizedRoute> optRoute = routeOptimizer.optimizeRoute(
                remainingBookings,
                route.getOffice(),
                route.getShiftStartTime(),
                DEFAULT_MAX_RIDE_TIME_MINUTES,
                DEFAULT_AVERAGE_SPEED_KMH,
                cab.getCapacity()
        );

        // 6. Revalidate constraints: if replanning fails constraints, throw exception to trigger rollback
        if (optRoute.isEmpty()) {
            log.error("Route replanning failed for route {}: remaining passengers violate route constraints", route.getId());
            throw new IllegalStateException(
                    "Cannot replan route " + route.getId() + " after booking cancellation: remaining passengers violate constraints"
            );
        }

        OptimizedRoute optimized = optRoute.get();

        // 7. Persist updated route attributes
        route.setTotalDistance(optimized.totalDistanceKm());
        route.setEstimatedArrivalTime(optimized.officeEta());
        route = routeRepository.save(route);

        // 8. Persist new RouteStop records with updated ETAs and sequences
        List<RouteStopResponse> stopResponses = new ArrayList<>();
        for (OptimizedStop newStop : optimized.stops()) {
            RouteStop rs = RouteStop.builder()
                    .route(route)
                    .booking(newStop.booking())
                    .sequence(newStop.sequence())
                    .pickupEta(newStop.pickupEta())
                    .rideDuration(newStop.rideDurationMinutes())
                    .latitude(newStop.latitude())
                    .longitude(newStop.longitude())
                    .build();
            rs = routeStopRepository.save(rs);
            stopResponses.add(mapToStopResponse(rs, newStop));
        }

        return Optional.of(mapToRouteResponse(route, stopResponses, optimized.requiresEscortGuard()));
    }

    private RouteResponse mapToRouteResponse(Route route, List<RouteStopResponse> stops, boolean requiresEscortGuard) {
        return RouteResponse.builder()
                .id(route.getId())
                .cab(CabResponse.builder()
                        .id(route.getCab().getId())
                        .vehicleNumber(route.getCab().getVehicleNumber())
                        .capacity(route.getCab().getCapacity())
                        .status(route.getCab().getStatus())
                        .build())
                .office(OfficeResponse.builder()
                        .id(route.getOffice().getId())
                        .name(route.getOffice().getName())
                        .address(route.getOffice().getAddress())
                        .latitude(route.getOffice().getLatitude())
                        .longitude(route.getOffice().getLongitude())
                        .build())
                .shiftStartTime(route.getShiftStartTime())
                .status(route.getStatus())
                .totalDistanceKm(route.getTotalDistance())
                .estimatedArrivalTime(route.getEstimatedArrivalTime())
                .requiresEscortGuard(requiresEscortGuard)
                .stops(stops)
                .build();
    }

    private RouteStopResponse mapToStopResponse(RouteStop stop, OptimizedStop optStop) {
        User user = stop.getBooking().getUser();
        return RouteStopResponse.builder()
                .id(stop.getId())
                .bookingId(stop.getBooking().getId())
                .employeeId(user != null ? user.getId() : null)
                .employeeName(user != null ? user.getName() : null)
                .sequence(stop.getSequence())
                .pickupEta(stop.getPickupEta())
                .rideDurationMinutes(stop.getRideDuration())
                .pickupLatitude(stop.getLatitude())
                .pickupLongitude(stop.getLongitude())
                .build();
    }
}
