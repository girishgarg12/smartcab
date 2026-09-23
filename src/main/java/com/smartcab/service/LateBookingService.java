package com.smartcab.service;

import com.smartcab.dto.*;
import com.smartcab.entity.*;
import com.smartcab.repository.BookingRepository;
import com.smartcab.repository.RouteRepository;
import com.smartcab.repository.RouteStopRepository;
import com.smartcab.routing.distance.DistanceCalculator;
import com.smartcab.routing.optimizer.OptimizedRoute;
import com.smartcab.routing.optimizer.OptimizedStop;
import com.smartcab.routing.optimizer.RouteOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service managing dynamic late booking insertion into already-generated cab routes.
 *
 * <h3>Algorithmic Flow:</h3>
 * <ol>
 *   <li>Identifies active confirmed routes for the same office and shift.</li>
 *   <li>Filters for vehicles with available passenger capacity.</li>
 *   <li>Ranks candidate routes deterministically based on spatial proximity to the new passenger.</li>
 *   <li>Re-optimizes candidate routes, strictly validating capacity, max ride time, office arrival, and night safety.</li>
 *   <li>Atomically inserts the passenger into the first valid candidate cab, leaving unrelated cabs untouched.</li>
 *   <li>Returns descriptive diagnostic reasons if no cab can accommodate the passenger.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LateBookingService {

    public static final int DEFAULT_MAX_RIDE_TIME_MINUTES = 60;
    public static final double DEFAULT_AVERAGE_SPEED_KMH = 30.0;

    private final BookingRepository bookingRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RouteOptimizer routeOptimizer;
    private final DistanceCalculator distanceCalculator;

    /**
     * Attempts to insert a late booking into an existing confirmed cab route.
     *
     * @param bookingId ID of the late booking
     * @return Result containing success details or a meaningful rejection reason
     */
    @Transactional
    public LateBookingInsertionResult tryInsertLateBooking(Long bookingId) {
        if (bookingId == null) {
            return LateBookingInsertionResult.rejected(null, "Booking ID must not be null");
        }

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return LateBookingInsertionResult.rejected(bookingId, "Booking not found with id: " + bookingId);
        }

        if (booking.getStatus() != BookingStatus.BOOKED) {
            return LateBookingInsertionResult.rejected(
                    bookingId,
                    "Booking is not in BOOKED status; current status: " + booking.getStatus()
            );
        }

        Office office = booking.getOffice();
        if (office == null) {
            return LateBookingInsertionResult.rejected(bookingId, "Booking is not associated with an office");
        }

        List<Route> existingRoutes = routeRepository.findByOfficeIdAndShiftStartTimeAndStatus(
                office.getId(), booking.getShiftStartTime(), RouteStatus.CONFIRMED
        );

        if (existingRoutes.isEmpty()) {
            return LateBookingInsertionResult.rejected(
                    bookingId,
                    String.format("No active routes found serving office '%s' for shift %s",
                            office.getName(), booking.getShiftStartTime())
            );
        }

        List<RouteCandidateRecord> candidatesWithSeats = new ArrayList<>();
        boolean anyRoutesFound = false;

        for (Route route : existingRoutes) {
            anyRoutesFound = true;
            List<RouteStop> stops = routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId());
            int capacity = route.getCab().getCapacity();
            int currentPassengers = stops.size();

            if (currentPassengers < capacity) {
                double minDistanceToRoute = stops.stream()
                        .mapToDouble(s -> distanceCalculator.calculateDistanceKm(
                                booking.getPickupLatitude(), booking.getPickupLongitude(),
                                s.getLatitude(), s.getLongitude()))
                        .min()
                        .orElse(distanceCalculator.calculateDistanceKm(
                                booking.getPickupLatitude(), booking.getPickupLongitude(),
                                office.getLatitude(), office.getLongitude()));

                candidatesWithSeats.add(new RouteCandidateRecord(route, stops, minDistanceToRoute));
            }
        }

        if (candidatesWithSeats.isEmpty()) {
            return LateBookingInsertionResult.rejected(
                    bookingId,
                    "All existing cabs serving this shift are at maximum capacity"
            );
        }

        candidatesWithSeats.sort(Comparator
                .comparingDouble(RouteCandidateRecord::minDistanceToRoute)
                .thenComparing(c -> c.route().getId()));

        for (RouteCandidateRecord candidate : candidatesWithSeats) {
            Route route = candidate.route();
            List<RouteStop> currentStops = candidate.stops();

            List<Booking> testBookings = new ArrayList<>(currentStops.stream().map(RouteStop::getBooking).toList());
            testBookings.add(booking);

            Optional<OptimizedRoute> optRoute = routeOptimizer.optimizeRoute(
                    testBookings,
                    office,
                    route.getShiftStartTime(),
                    DEFAULT_MAX_RIDE_TIME_MINUTES,
                    DEFAULT_AVERAGE_SPEED_KMH,
                    route.getCab().getCapacity()
            );

            if (optRoute.isPresent()) {
                OptimizedRoute optimized = optRoute.get();
                log.info("Successfully inserted late booking {} into route {} (cab {})",
                        booking.getId(), route.getId(), route.getCab().getVehicleNumber());

                route.setTotalDistance(optimized.totalDistanceKm());
                route.setEstimatedArrivalTime(optimized.officeEta());
                route = routeRepository.save(route);

                routeStopRepository.deleteAll(currentStops);

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

                booking.setStatus(BookingStatus.ASSIGNED);
                bookingRepository.save(booking);

                RouteResponse routeResponse = mapToRouteResponse(route, stopResponses, optimized.requiresEscortGuard());
                return LateBookingInsertionResult.success(
                        bookingId,
                        route.getId(),
                        route.getCab().getId(),
                        route.getCab().getVehicleNumber(),
                        routeResponse
                );
            }
        }

        return LateBookingInsertionResult.rejected(
                bookingId,
                "No existing cab could accept the booking without violating hard constraints (maximum ride time or night safety rules)"
        );
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

    private record RouteCandidateRecord(Route route, List<RouteStop> stops, double minDistanceToRoute) {}
}
