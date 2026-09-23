package com.smartcab.routing.optimizer;

import com.smartcab.entity.Booking;

import java.time.LocalDateTime;

/**
 * Represents an individual sequenced pickup stop along an optimized cab route.
 *
 * @param booking             Associated passenger booking
 * @param sequence            1-based sequence order of this pickup stop
 * @param pickupEta           Estimated arrival time for passenger pickup
 * @param rideDurationMinutes Duration in minutes from this pickup until office arrival
 * @param latitude            Pickup latitude
 * @param longitude           Pickup longitude
 */
public record OptimizedStop(
        Booking booking,
        int sequence,
        LocalDateTime pickupEta,
        int rideDurationMinutes,
        double latitude,
        double longitude
) {
}
