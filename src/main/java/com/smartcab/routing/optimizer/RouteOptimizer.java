package com.smartcab.routing.optimizer;

import com.smartcab.entity.Booking;
import com.smartcab.entity.Office;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for optimizing the pickup sequence for passengers assigned to a single cab.
 */
public interface RouteOptimizer {

    /**
     * Determines the optimal pickup route sequence for a cab cluster ending at the destination office.
     * Uses default maximum cab capacity (6).
     *
     * @param bookings           Bookings assigned to this cab (at most 6 passengers)
     * @param office             Destination office location
     * @param shiftStartTime     Required office arrival deadline
     * @param maxRideTimeMinutes Maximum permissible in-cab travel duration for any passenger
     * @param averageSpeedKmh    Average vehicle travel speed in km/h
     * @return Optional containing the lowest-distance valid {@link OptimizedRoute}, or empty if no route satisfies all hard constraints
     */
    default Optional<OptimizedRoute> optimizeRoute(
            List<Booking> bookings,
            Office office,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh) {
        return optimizeRoute(bookings, office, shiftStartTime, maxRideTimeMinutes, averageSpeedKmh, 6);
    }

    /**
     * Determines the optimal pickup route sequence for a cab cluster ending at the destination office,
     * taking into account a specific vehicle capacity.
     *
     * @param bookings           Bookings assigned to this cab
     * @param office             Destination office location
     * @param shiftStartTime     Required office arrival deadline
     * @param maxRideTimeMinutes Maximum permissible in-cab travel duration for any passenger
     * @param averageSpeedKmh    Average vehicle travel speed in km/h
     * @param cabCapacity        Vehicle passenger capacity
     * @return Optional containing the lowest-distance valid {@link OptimizedRoute}, or empty if no route satisfies all hard constraints
     */
    Optional<OptimizedRoute> optimizeRoute(
            List<Booking> bookings,
            Office office,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes,
            double averageSpeedKmh,
            int cabCapacity
    );
}
