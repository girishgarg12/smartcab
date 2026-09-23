package com.smartcab.routing.constraint;

import com.smartcab.entity.Booking;
import com.smartcab.entity.Office;
import com.smartcab.routing.optimizer.OptimizedStop;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Contextual model containing parameters and sequenced stops of a proposed cab route candidate
 * for constraint evaluation.
 *
 * @param bookings           Bookings assigned to the route
 * @param stops              Sequenced pickup stops with ETAs and ride durations
 * @param cabCapacity        Cab maximum passenger capacity
 * @param office             Destination office
 * @param officeEta          Estimated arrival timestamp at the office
 * @param shiftStartTime     Required office arrival deadline
 * @param maxRideTimeMinutes Maximum permissible ride duration in minutes
 * @param hasEscortGuard     Whether an escort guard is present in the cab
 */
public record RouteCandidate(
        List<Booking> bookings,
        List<OptimizedStop> stops,
        int cabCapacity,
        Office office,
        LocalDateTime officeEta,
        LocalDateTime shiftStartTime,
        int maxRideTimeMinutes,
        boolean hasEscortGuard
) {
    public RouteCandidate(
            List<Booking> bookings,
            List<OptimizedStop> stops,
            int cabCapacity,
            Office office,
            LocalDateTime officeEta,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes) {
        this(bookings, stops, cabCapacity, office, officeEta, shiftStartTime, maxRideTimeMinutes, false);
    }
}
