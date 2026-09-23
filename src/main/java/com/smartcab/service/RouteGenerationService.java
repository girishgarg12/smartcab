package com.smartcab.service;

import com.smartcab.dto.*;
import com.smartcab.entity.*;
import com.smartcab.repository.BookingRepository;
import com.smartcab.repository.CabRepository;
import com.smartcab.repository.RouteRepository;
import com.smartcab.repository.RouteStopRepository;
import com.smartcab.routing.clustering.EmployeeClusterer;
import com.smartcab.routing.optimizer.OptimizedRoute;
import com.smartcab.routing.optimizer.OptimizedStop;
import com.smartcab.routing.optimizer.RouteOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service orchestrating the complete end-to-end route generation workflow:
 * <ol>
 *   <li>Querying active unassigned employee bookings grouped by office and shift</li>
 *   <li>Clustering bookings into cab-sized groups using {@link EmployeeClusterer}</li>
 *   <li>Assigning available cabs from the vehicle fleet</li>
 *   <li>Optimizing pickup stops and enforcing hard constraints via {@link RouteOptimizer}</li>
 *   <li>Persisting {@link Route} and {@link RouteStop} entities atomically</li>
 *   <li>Updating booking and cab statuses to {@code ASSIGNED}</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouteGenerationService {

    public static final double DEFAULT_MAX_DETOUR_KM = 5.0;
    public static final int DEFAULT_MAX_RIDE_TIME_MINUTES = 60;
    public static final double DEFAULT_AVERAGE_SPEED_KMH = 30.0;
    public static final int DEFAULT_CAB_CAPACITY = 4;

    private final BookingRepository bookingRepository;
    private final CabRepository cabRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final EmployeeClusterer employeeClusterer;
    private final RouteOptimizer routeOptimizer;

    /**
     * Executes the route generation pipeline for pending employee bookings.
     *
     * @param request Optional filtering and configuration parameters
     * @return List of generated route summaries
     */
    @Transactional
    public List<RouteResponse> generateRoutes(RouteGenerationRequest request) {
        double maxDetourKm = (request != null && request.getMaxDetourKm() != null)
                ? request.getMaxDetourKm() : DEFAULT_MAX_DETOUR_KM;
        int maxRideTimeMinutes = (request != null && request.getMaxRideTimeMinutes() != null)
                ? request.getMaxRideTimeMinutes() : DEFAULT_MAX_RIDE_TIME_MINUTES;
        double averageSpeedKmh = (request != null && request.getAverageSpeedKmh() != null)
                ? request.getAverageSpeedKmh() : DEFAULT_AVERAGE_SPEED_KMH;
        int targetCabCapacity = (request != null && request.getCabCapacity() != null)
                ? request.getCabCapacity() : DEFAULT_CAB_CAPACITY;
        boolean forceRegenerate = request != null && Boolean.TRUE.equals(request.getForceRegenerate());

        // 1. Find active bookings (do not regenerate ASSIGNED bookings unless explicitly requested)
        List<Booking> candidateBookings;
        if (forceRegenerate) {
            candidateBookings = bookingRepository.findByStatusIn(List.of(BookingStatus.BOOKED, BookingStatus.ASSIGNED));
        } else {
            candidateBookings = bookingRepository.findByStatus(BookingStatus.BOOKED);
        }

        if (request != null) {
            if (request.getOfficeId() != null) {
                candidateBookings = candidateBookings.stream()
                        .filter(b -> b.getOffice() != null && request.getOfficeId().equals(b.getOffice().getId()))
                        .toList();
            }
            if (request.getShiftStartTime() != null) {
                candidateBookings = candidateBookings.stream()
                        .filter(b -> request.getShiftStartTime().isEqual(b.getShiftStartTime()))
                        .toList();
            }
        }

        if (candidateBookings.isEmpty()) {
            log.info("No active bookings found matching criteria for route generation");
            return Collections.emptyList();
        }

        // Group active bookings by (Office, ShiftStartTime)
        Map<OfficeShiftKey, List<Booking>> grouped = candidateBookings.stream()
                .collect(Collectors.groupingBy(b -> new OfficeShiftKey(b.getOffice().getId(), b.getShiftStartTime())));

        // 2. Fetch available cabs
        List<Cab> availableCabs = new ArrayList<>(cabRepository.findByStatus(CabStatus.AVAILABLE));
        log.info("Found {} available cabs for {} booking groups", availableCabs.size(), grouped.size());

        List<RouteResponse> generatedRoutes = new ArrayList<>();

        for (Map.Entry<OfficeShiftKey, List<Booking>> entry : grouped.entrySet()) {
            List<Booking> groupBookings = entry.getValue();
            if (groupBookings.isEmpty()) {
                continue;
            }

            Office office = groupBookings.get(0).getOffice();
            LocalDateTime shiftStartTime = entry.getKey().shiftStartTime();

            // 3. Cluster bookings into cab-sized groups using EmployeeClusterer
            List<List<Booking>> clusters = employeeClusterer.clusterBookings(
                    groupBookings, targetCabCapacity, maxDetourKm
            );

            log.info("Clustered {} bookings into {} cab groups for office '{}' and shift {}",
                    groupBookings.size(), clusters.size(), office.getName(), shiftStartTime);

            for (List<Booking> cluster : clusters) {
                if (cluster.isEmpty()) {
                    continue;
                }

                if (availableCabs.isEmpty()) {
                    log.warn("No available cabs left to assign cluster of size {}", cluster.size());
                    break;
                }

                // 4. Assign available cab with sufficient capacity
                Optional<Cab> matchingCabOpt = availableCabs.stream()
                        .filter(cab -> cab.getCapacity() >= cluster.size())
                        .findFirst();

                if (matchingCabOpt.isEmpty()) {
                    log.warn("No available cab with capacity >= {} for cluster", cluster.size());
                    continue;
                }

                Cab cab = matchingCabOpt.get();

                // 5. Optimize pickup route and validate constraints (capacity, max ride time, arrival, night safety)
                Optional<OptimizedRoute> optimizedRouteOpt = routeOptimizer.optimizeRoute(
                        cluster,
                        office,
                        shiftStartTime,
                        maxRideTimeMinutes,
                        averageSpeedKmh,
                        cab.getCapacity()
                );

                if (optimizedRouteOpt.isEmpty()) {
                    log.warn("No valid route could be constructed for cluster of size {} in cab {} (constraints violated)",
                            cluster.size(), cab.getVehicleNumber());
                    continue;
                }

                OptimizedRoute optimizedRoute = optimizedRouteOpt.get();

                // Successfully formed route: consume cab and persist atomically
                availableCabs.remove(cab);
                cab.setStatus(CabStatus.ASSIGNED);
                cabRepository.save(cab);

                // 6. Persist Route
                Route route = Route.builder()
                        .cab(cab)
                        .office(office)
                        .shiftStartTime(shiftStartTime)
                        .status(RouteStatus.CONFIRMED)
                        .totalDistance(optimizedRoute.totalDistanceKm())
                        .estimatedArrivalTime(optimizedRoute.officeEta())
                        .build();
                route = routeRepository.save(route);

                // 7. Persist RouteStops & Update Bookings to ASSIGNED
                List<RouteStopResponse> stopResponses = new ArrayList<>();
                for (OptimizedStop stop : optimizedRoute.stops()) {
                    Booking booking = stop.booking();

                    RouteStop routeStop = RouteStop.builder()
                            .route(route)
                            .booking(booking)
                            .sequence(stop.sequence())
                            .pickupEta(stop.pickupEta())
                            .rideDuration(stop.rideDurationMinutes())
                            .latitude(stop.latitude())
                            .longitude(stop.longitude())
                            .build();
                    routeStop = routeStopRepository.save(routeStop);

                    booking.setStatus(BookingStatus.ASSIGNED);
                    bookingRepository.save(booking);

                    stopResponses.add(mapToStopResponse(routeStop, stop));
                }

                // 8. Add route summary to output
                generatedRoutes.add(mapToRouteResponse(route, stopResponses, optimizedRoute.requiresEscortGuard()));
            }
        }

        return generatedRoutes;
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

    private record OfficeShiftKey(Long officeId, LocalDateTime shiftStartTime) {}
}
