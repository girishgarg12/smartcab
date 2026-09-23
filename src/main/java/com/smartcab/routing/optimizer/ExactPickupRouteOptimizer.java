package com.smartcab.routing.optimizer;

import com.smartcab.entity.Booking;
import com.smartcab.entity.Office;
import com.smartcab.routing.constraint.*;
import com.smartcab.routing.distance.DistanceCalculator;
import com.smartcab.routing.distance.HaversineDistanceCalculator;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ExactPickupRouteOptimizer implements RouteOptimizer {

    public static final int MAX_SUPPORTED_CAB_SIZE = 6;

    private final DistanceCalculator distanceCalculator;
    private final List<RouteConstraint> constraints;

    public ExactPickupRouteOptimizer() {
        this(new HaversineDistanceCalculator(), List.of(
                new CapacityConstraint(),
                new MaxRideTimeConstraint(),
                new OfficeArrivalConstraint()
        ));
    }

    public ExactPickupRouteOptimizer(DistanceCalculator distanceCalculator) {
        this(distanceCalculator, List.of(
                new CapacityConstraint(),
                new MaxRideTimeConstraint(),
                new OfficeArrivalConstraint()
        ));
    }

    @Override
    public Optional<OptimizedRoute> optimizeRoute(
            List<Booking> bookings,
            Office office,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh) {

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

        // Fast-path capacity check before generating permutations
        RouteCandidate initialCheckCandidate = new RouteCandidate(
                bookings, Collections.emptyList(), MAX_SUPPORTED_CAB_SIZE,
                office, shiftStartTime, shiftStartTime, maxRideTimeMinutes
        );
        for (RouteConstraint constraint : constraints) {
            if (constraint instanceof CapacityConstraint) {
                ConstraintValidationResult result = constraint.validate(initialCheckCandidate);
                if (!result.isValid()) {
                    return Optional.empty();
                }
            }
        }

        // Generate all k! permutations of passenger pickup orders
        List<List<Booking>> permutations = new ArrayList<>();
        generatePermutations(new ArrayList<>(bookings), 0, permutations);

        OptimizedRoute bestRoute = null;
        double minTotalDistance = Double.MAX_VALUE;

        // Office arrival must be at or before shiftStartTime
        LocalDateTime officeEta = shiftStartTime;

        for (List<Booking> permutation : permutations) {
            CandidateEvaluation evaluation = evaluatePermutation(
                    permutation,
                    office,
                    officeEta,
                    shiftStartTime,
                    maxRideTimeMinutes,
                    averageSpeedKmh
            );

            if (evaluation.isValid()) {
                if (evaluation.totalDistanceKm() < minTotalDistance) {
                    minTotalDistance = evaluation.totalDistanceKm();
                    bestRoute = evaluation.route();
                }
            }
        }

        return Optional.ofNullable(bestRoute);
    }

    private CandidateEvaluation evaluatePermutation(
            List<Booking> permutation,
            Office office,
            LocalDateTime officeEta,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh) {

        int k = permutation.size();
        double[] legDistancesKm = new double[k];

        // 1. Calculate leg distances
        for (int i = 0; i < k - 1; i++) {
            Booking from = permutation.get(i);
            Booking to = permutation.get(i + 1);
            legDistancesKm[i] = distanceCalculator.calculateDistanceKm(
                    from.getPickupLatitude(), from.getPickupLongitude(),
                    to.getPickupLatitude(), to.getPickupLongitude()
            );
        }

        // Final leg from last passenger to the office
        Booking lastBooking = permutation.get(k - 1);
        legDistancesKm[k - 1] = distanceCalculator.calculateDistanceKm(
                lastBooking.getPickupLatitude(), lastBooking.getPickupLongitude(),
                office.getLatitude(), office.getLongitude()
        );

        // Total route distance
        double totalDistanceKm = 0.0;
        for (double leg : legDistancesKm) {
            totalDistanceKm += leg;
        }

        // 2. Compute travel durations and working-backward pickup ETAs
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

        // 3. Validate against all registered route constraints
        RouteCandidate candidate = new RouteCandidate(
                permutation,
                stops,
                MAX_SUPPORTED_CAB_SIZE,
                office,
                officeEta,
                shiftStartTime,
                maxRideTimeMinutes
        );

        for (RouteConstraint constraint : constraints) {
            ConstraintValidationResult result = constraint.validate(candidate);
            if (!result.isValid()) {
                return CandidateEvaluation.invalid(result.failureReason());
            }
        }

        OptimizedRoute route = new OptimizedRoute(stops, totalDistanceKm, officeEta, office);
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
