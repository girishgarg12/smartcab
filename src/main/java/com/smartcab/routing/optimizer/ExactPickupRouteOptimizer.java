package com.smartcab.routing.optimizer;

import com.smartcab.entity.Booking;
import com.smartcab.entity.Office;
import com.smartcab.routing.constraint.*;
import com.smartcab.routing.distance.DistanceCalculator;
import com.smartcab.routing.distance.HaversineDistanceCalculator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Exact, exhaustive permutation-based route optimizer for a single cab group.
 *
 * <h3>Exactness for Small Cab Sizes:</h3>
 * <p>
 * For standard cab capacities where \(k \le 6\), the total number of pickup permutations is at most
 * \(6! = 720\). Evaluating 720 candidate routes against travel time and distance constraints executes
 * in less than a millisecond on modern hardware, allowing this component to find the <i>provably optimal</i>
 * pickup sequence for the assigned cab group.
 * </p>
 *
 * <h3>Night Safety & Guard Escalation:</h3>
 * <p>
 * During night hours, women employees cannot be the first pickup alone without an escort guard.
 * The optimizer uses a two-phase strategy:
 * <ol>
 *   <li><b>Phase 1 (Unescorted Reordering):</b> Evaluates all pickup permutations without a guard.
 *       If an alternative valid ordering exists where a female employee is not the first pickup alone,
 *       that ordering is selected.</li>
 *   <li><b>Phase 2 (Escort Guard Escalation):</b> If all valid permutations violate the night safety rule,
 *       the optimizer evaluates routes with a security escort guard (occupying 1 cab seat).
 *       If the cab has sufficient capacity (\(k + 1 \le \text{cabCapacity}\)), an escorted route is returned.</li>
 * </ol>
 * If no safe ordering can be formed and no seat is available for an escort guard, the route is rejected
 * (returns {@link Optional#empty()}) to prevent silent safety violations.
 * </p>
 *
 * <h3>VRP Scope & Complexity Disclaimer:</h3>
 * <p>
 * <b>Important:</b> While this algorithm is exact for a single cab group of size \(k \le 6\), it does
 * <b>not</b> claim to solve the general Capacitated Vehicle Routing Problem (CVRP) or Traveling Salesperson
 * Problem (TSP) for arbitrary fleet or passenger sizes. CVRP is strictly NP-hard. Full-fleet optimization
 * relies on our two-phase heuristic: polynomial spatial clustering followed by exact per-cab route sequencing.
 * </p>
 *
 * <h3>Complexity Analysis:</h3>
 * <ul>
 *   <li><b>Time Complexity:</b> \(\mathcal{O}(k! \cdot k)\), where \(k\) is the number of passenger pickups in the cab
 *       (\(k \le 6\)). For each permutation, leg distances, travel durations, and constraint validations are evaluated in \(\mathcal{O}(k)\).
 *   </li>
 *   <li><b>Space Complexity:</b> \(\mathcal{O}(k)\) auxiliary memory for the permutation recursion stack and stop sequencing.</li>
 * </ul>
 */
@Component
public class ExactPickupRouteOptimizer implements RouteOptimizer {

    public static final int MAX_SUPPORTED_CAB_SIZE = 6;

    private final DistanceCalculator distanceCalculator;
    private final List<RouteConstraint> constraints;

    public ExactPickupRouteOptimizer() {
        this(new HaversineDistanceCalculator(), List.of(
                new CapacityConstraint(),
                new MaxRideTimeConstraint(),
                new OfficeArrivalConstraint(),
                new NightSafetyConstraint()
        ));
    }

    public ExactPickupRouteOptimizer(DistanceCalculator distanceCalculator) {
        this(distanceCalculator, List.of(
                new CapacityConstraint(),
                new MaxRideTimeConstraint(),
                new OfficeArrivalConstraint(),
                new NightSafetyConstraint()
        ));
    }

    public ExactPickupRouteOptimizer(DistanceCalculator distanceCalculator, List<RouteConstraint> constraints) {
        this.distanceCalculator = distanceCalculator;
        this.constraints = constraints;
    }

    @Override
    public Optional<OptimizedRoute> optimizeRoute(
            List<Booking> bookings,
            Office office,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh) {
        return optimizeRoute(bookings, office, shiftStartTime, maxRideTimeMinutes, averageSpeedKmh, MAX_SUPPORTED_CAB_SIZE);
    }

    @Override
    public Optional<OptimizedRoute> optimizeRoute(
            List<Booking> bookings,
            Office office,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh,
            int cabCapacity) {

        validateInputs(bookings, office, shiftStartTime, maxRideTimeMinutes, averageSpeedKmh);

        if (bookings == null || bookings.isEmpty()) {
            return Optional.empty();
        }

        int k = bookings.size();
        if (k > MAX_SUPPORTED_CAB_SIZE) {
            throw new IllegalArgumentException(
                    "Exact route optimization is only supported for up to " + MAX_SUPPORTED_CAB_SIZE
                            + " passengers per cab. Received: " + k);
        }

        if (k > cabCapacity) {
            return Optional.empty();
        }

        List<List<Booking>> permutations = new ArrayList<>();
        generatePermutations(new ArrayList<>(bookings), 0, permutations);

        LocalDateTime officeEta = shiftStartTime;

        OptimizedRoute bestRouteWithoutGuard = findBestRoute(
                permutations, office, officeEta, shiftStartTime,
                maxRideTimeMinutes, averageSpeedKmh, cabCapacity, false
        );

        if (bestRouteWithoutGuard != null) {
            return Optional.of(bestRouteWithoutGuard);
        }

        if (k + 1 <= cabCapacity) {
            OptimizedRoute bestRouteWithGuard = findBestRoute(
                    permutations, office, officeEta, shiftStartTime,
                    maxRideTimeMinutes, averageSpeedKmh, cabCapacity, true
            );
            if (bestRouteWithGuard != null) {
                return Optional.of(bestRouteWithGuard);
            }
        }

        return Optional.empty();
    }

    private OptimizedRoute findBestRoute(
            List<List<Booking>> permutations,
            Office office,
            LocalDateTime officeEta,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh,
            int cabCapacity,
            boolean hasEscortGuard) {

        OptimizedRoute bestRoute = null;
        double minTotalDistance = Double.MAX_VALUE;

        for (List<Booking> permutation : permutations) {
            CandidateEvaluation evaluation = evaluatePermutation(
                    permutation,
                    office,
                    officeEta,
                    shiftStartTime,
                    maxRideTimeMinutes,
                    averageSpeedKmh,
                    cabCapacity,
                    hasEscortGuard
            );

            if (evaluation.isValid()) {
                if (evaluation.totalDistanceKm() < minTotalDistance) {
                    minTotalDistance = evaluation.totalDistanceKm();
                    bestRoute = evaluation.route();
                }
            }
        }

        return bestRoute;
    }

    private CandidateEvaluation evaluatePermutation(
            List<Booking> permutation,
            Office office,
            LocalDateTime officeEta,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh,
            int cabCapacity,
            boolean hasEscortGuard) {

        int k = permutation.size();
        double[] legDistancesKm = new double[k];

        for (int i = 0; i < k - 1; i++) {
            Booking from = permutation.get(i);
            Booking to = permutation.get(i + 1);
            legDistancesKm[i] = distanceCalculator.calculateDistanceKm(
                    from.getPickupLatitude(), from.getPickupLongitude(),
                    to.getPickupLatitude(), to.getPickupLongitude()
            );
        }

        Booking lastBooking = permutation.get(k - 1);
        legDistancesKm[k - 1] = distanceCalculator.calculateDistanceKm(
                lastBooking.getPickupLatitude(), lastBooking.getPickupLongitude(),
                office.getLatitude(), office.getLongitude()
        );

        double totalDistanceKm = 0.0;
        for (double leg : legDistancesKm) {
            totalDistanceKm += leg;
        }

        long[] legDurationsSeconds = new long[k];
        for (int i = 0; i < k; i++) {
            double hours = legDistancesKm[i] / averageSpeedKmh;
            legDurationsSeconds[i] = Math.round(hours * 3600.0);
        }

        LocalDateTime[] pickupEtas = new LocalDateTime[k];
        pickupEtas[k - 1] = officeEta.minusSeconds(legDurationsSeconds[k - 1]);

        for (int i = k - 2; i >= 0; i--) {
            pickupEtas[i] = pickupEtas[i + 1].minusSeconds(legDurationsSeconds[i]);
        }

        List<OptimizedStop> stops = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            Booking b = permutation.get(i);
            long rideSeconds = Duration.between(pickupEtas[i], officeEta).getSeconds();
            int rideDurationMinutes = (int) Math.ceil(rideSeconds / 60.0);

            stops.add(new OptimizedStop(
                    b,
                    i + 1,
                    pickupEtas[i],
                    rideDurationMinutes,
                    b.getPickupLatitude(),
                    b.getPickupLongitude()
            ));
        }

        RouteCandidate candidate = new RouteCandidate(
                permutation,
                stops,
                cabCapacity,
                office,
                officeEta,
                shiftStartTime,
                maxRideTimeMinutes,
                hasEscortGuard
        );

        for (RouteConstraint constraint : constraints) {
            ConstraintValidationResult result = constraint.validate(candidate);
            if (!result.isValid()) {
                return CandidateEvaluation.invalid(result.failureReason());
            }
        }

        OptimizedRoute route = new OptimizedRoute(stops, totalDistanceKm, officeEta, office, hasEscortGuard);
        return new CandidateEvaluation(true, totalDistanceKm, route, null);
    }

    private void generatePermutations(List<Booking> items, int index, List<List<Booking>> result) {
        if (index == items.size()) {
            result.add(new ArrayList<>(items));
            return;
        }
        for (int i = index; i < items.size(); i++) {
            Collections.swap(items, index, i);
            generatePermutations(items, index + 1, result);
            Collections.swap(items, index, i);
        }
    }

    private void validateInputs(
            List<Booking> bookings,
            Office office,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh) {

        if (office == null || office.getLatitude() == null || office.getLongitude() == null) {
            throw new IllegalArgumentException("Destination office and coordinates must not be null");
        }
        if (shiftStartTime == null) {
            throw new IllegalArgumentException("Shift start time must not be null");
        }
        if (maxRideTimeMinutes <= 0) {
            throw new IllegalArgumentException("Maximum ride time must be positive. Found: " + maxRideTimeMinutes);
        }
        if (averageSpeedKmh <= 0.0) {
            throw new IllegalArgumentException("Average speed must be positive. Found: " + averageSpeedKmh);
        }
    }

    private record CandidateEvaluation(boolean isValid, double totalDistanceKm, OptimizedRoute route, String failureReason) {
        public static CandidateEvaluation invalid(String reason) {
            return new CandidateEvaluation(false, Double.MAX_VALUE, null, reason);
        }
    }
}
