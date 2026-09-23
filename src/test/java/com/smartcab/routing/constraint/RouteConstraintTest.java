package com.smartcab.routing.constraint;

import com.smartcab.entity.Booking;
import com.smartcab.entity.Office;
import com.smartcab.routing.optimizer.OptimizedStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteConstraintTest {

    private CapacityConstraint capacityConstraint;
    private MaxRideTimeConstraint maxRideTimeConstraint;
    private OfficeArrivalConstraint officeArrivalConstraint;

    private Office office;
    private LocalDateTime shiftStart;

    @BeforeEach
    void setUp() {
        capacityConstraint = new CapacityConstraint();
        maxRideTimeConstraint = new MaxRideTimeConstraint();
        officeArrivalConstraint = new OfficeArrivalConstraint();

        office = Office.builder()
                .id(1L)
                .name("HQ Tech Hub")
                .latitude(12.9200)
                .longitude(77.6800)
                .build();

        shiftStart = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
    }

    private RouteCandidate buildCandidate(
            int passengerCount,
            int cabCapacity,
            List<OptimizedStop> stops,
            LocalDateTime officeEta,
            LocalDateTime shiftStartTime,
            int maxRideTimeMinutes) {

        List<Booking> bookings = java.util.stream.IntStream.rangeClosed(1, passengerCount)
                .mapToObj(i -> Booking.builder().id((long) i).build())
                .toList();

        return new RouteCandidate(
                bookings,
                stops,
                cabCapacity,
                office,
                officeEta,
                shiftStartTime,
                maxRideTimeMinutes
        );
    }

    @Test
    void testCapacityConstraintPassAndViolation() {
        // Valid: 4 passengers in capacity 4
        RouteCandidate validCandidate = buildCandidate(4, 4, List.of(), shiftStart, shiftStart, 45);
        ConstraintValidationResult passResult = capacityConstraint.validate(validCandidate);
        assertTrue(passResult.isValid());
        assertNull(passResult.failureReason());

        // Violation: 5 passengers in capacity 4
        RouteCandidate invalidCandidate = buildCandidate(5, 4, List.of(), shiftStart, shiftStart, 45);
        ConstraintValidationResult failResult = capacityConstraint.validate(invalidCandidate);
        assertFalse(failResult.isValid());
        assertEquals("CapacityConstraint", failResult.constraintName());
        assertTrue(failResult.failureReason().contains("exceeds maximum cab capacity"));
    }

    @Test
    void testMaxRideTimeConstraintPassAndViolation() {
        Booking b1 = Booking.builder().id(1L).build();
        Booking b2 = Booking.builder().id(2L).build();

        // Valid: both stops have ride time <= 45 min
        List<OptimizedStop> validStops = List.of(
                new OptimizedStop(b1, 1, shiftStart.minusMinutes(40), 40, 12.93, 77.67),
                new OptimizedStop(b2, 2, shiftStart.minusMinutes(15), 15, 12.92, 77.68)
        );
        RouteCandidate validCandidate = buildCandidate(2, 4, validStops, shiftStart, shiftStart, 45);
        ConstraintValidationResult passResult = maxRideTimeConstraint.validate(validCandidate);
        assertTrue(passResult.isValid());

        // Violation: stop 1 ride time is 50 min > max 45 min
        List<OptimizedStop> invalidStops = List.of(
                new OptimizedStop(b1, 1, shiftStart.minusMinutes(50), 50, 12.93, 77.67),
                new OptimizedStop(b2, 2, shiftStart.minusMinutes(15), 15, 12.92, 77.68)
        );
        RouteCandidate invalidCandidate = buildCandidate(2, 4, invalidStops, shiftStart, shiftStart, 45);
        ConstraintValidationResult failResult = maxRideTimeConstraint.validate(invalidCandidate);
        assertFalse(failResult.isValid());
        assertEquals("MaxRideTimeConstraint", failResult.constraintName());
        assertTrue(failResult.failureReason().contains("exceeds maximum allowed limit"));
    }

    @Test
    void testOfficeArrivalConstraintPassAndViolation() {
        // Valid: arrives on time (09:00:00 <= 09:00:00)
        RouteCandidate onTimeCandidate = buildCandidate(1, 4, List.of(), shiftStart, shiftStart, 45);
        ConstraintValidationResult onTimeResult = officeArrivalConstraint.validate(onTimeCandidate);
        assertTrue(onTimeResult.isValid());

        // Valid: arrives early (08:50:00 <= 09:00:00)
        RouteCandidate earlyCandidate = buildCandidate(1, 4, List.of(), shiftStart.minusMinutes(10), shiftStart, 45);
        ConstraintValidationResult earlyResult = officeArrivalConstraint.validate(earlyCandidate);
        assertTrue(earlyResult.isValid());

        // Violation: late arrival (09:05:00 > 09:00:00)
        RouteCandidate lateCandidate = buildCandidate(1, 4, List.of(), shiftStart.plusMinutes(5), shiftStart, 45);
        ConstraintValidationResult lateResult = officeArrivalConstraint.validate(lateCandidate);
        assertFalse(lateResult.isValid());
        assertEquals("OfficeArrivalConstraint", lateResult.constraintName());
        assertTrue(lateResult.failureReason().contains("is after shift start time"));
    }

    @Test
    void testValidRoutePassesAllConstraints() {
        Booking b1 = Booking.builder().id(1L).build();
        Booking b2 = Booking.builder().id(2L).build();

        List<OptimizedStop> validStops = List.of(
                new OptimizedStop(b1, 1, shiftStart.minusMinutes(25), 25, 12.93, 77.67),
                new OptimizedStop(b2, 2, shiftStart.minusMinutes(10), 10, 12.92, 77.68)
        );

        RouteCandidate candidate = buildCandidate(2, 4, validStops, shiftStart, shiftStart, 45);

        assertTrue(capacityConstraint.validate(candidate).isValid());
        assertTrue(maxRideTimeConstraint.validate(candidate).isValid());
        assertTrue(officeArrivalConstraint.validate(candidate).isValid());
    }
}
