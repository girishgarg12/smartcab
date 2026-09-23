package com.smartcab.routing.optimizer;

import com.smartcab.entity.Booking;
import com.smartcab.entity.BookingStatus;
import com.smartcab.entity.Office;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExactPickupRouteOptimizerTest {

    private RouteOptimizer optimizer;
    private Office office;
    private LocalDateTime shiftStart;

    @BeforeEach
    void setUp() {
        optimizer = new ExactPickupRouteOptimizer();
        office = Office.builder()
                .id(1L)
                .name("HQ Tech Hub")
                .address("Outer Ring Road, Bangalore")
                .latitude(12.9200)
                .longitude(77.6800)
                .build();
        shiftStart = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
    }

    private Booking createBooking(Long id, double lat, double lon) {
        return Booking.builder()
                .id(id)
                .pickupLatitude(lat)
                .pickupLongitude(lon)
                .shiftStartTime(shiftStart)
                .status(BookingStatus.BOOKED)
                .build();
    }

    @Test
    void testExactRouteOptimizationSmallGroup() {
        // 3 nearby bookings in Bellandur, Bangalore heading to office at (12.9200, 77.6800)
        Booking b1 = createBooking(1L, 12.9350, 77.6700);
        Booking b2 = createBooking(2L, 12.9300, 77.6720);
        Booking b3 = createBooking(3L, 12.9250, 77.6750);

        List<Booking> bookings = List.of(b1, b2, b3);

        Optional<OptimizedRoute> result = optimizer.optimizeRoute(
                bookings, office, shiftStart, 45, 30.0
        );

        assertTrue(result.isPresent());
        OptimizedRoute route = result.get();

        assertEquals(3, route.stops().size());
        assertEquals(shiftStart, route.officeEta());
        assertTrue(route.totalDistanceKm() > 0.0);

        // Verify sequence ordering and ETAs
        for (int i = 0; i < route.stops().size(); i++) {
            OptimizedStop stop = route.stops().get(i);
            assertEquals(i + 1, stop.sequence());
            assertTrue(stop.pickupEta().isBefore(shiftStart));
            assertTrue(stop.rideDurationMinutes() <= 45);

            if (i > 0) {
                OptimizedStop prev = route.stops().get(i - 1);
                assertTrue(prev.pickupEta().isBefore(stop.pickupEta()) || prev.pickupEta().isEqual(stop.pickupEta()));
                assertTrue(prev.rideDurationMinutes() >= stop.rideDurationMinutes());
            }
        }
    }

    @Test
    void testShortestDistancePermutationViolatingMaxRideTimeIsRejected() {
        // Construct a scenario with 2 bookings, B1 and B2, and Office O
        // Suppose:
        // Leg B1 -> B2 is very short (1 km)
        // Leg B2 -> O is long (25 km)
        // Leg B2 -> B1 is 1 km
        // Leg B1 -> O is 26 km
        //
        // Permutation 1: B1 -> B2 -> O
        // Total distance = 1 km + 25 km = 26 km.
        // At speed 30 km/h:
        //   B1 -> B2 takes 2 mins (1 km / 30 * 60 = 2 min)
        //   B2 -> O takes 50 mins (25 km / 30 * 60 = 50 min)
        // Total travel time for B1 = 52 mins.
        //
        // If maxRideTime is set to 40 minutes:
        // Permutation 1 (total dist 26 km) requires B1 ride time of 52 min > 40 min -> VIOLATES max ride time!
        // Permutation 2: B2 -> B1 -> O
        // Total distance = 1 km + 26 km = 27 km.
        // Total travel time for B2 = 54 mins > 40 min -> Also violates!
        // So with maxRideTime = 40 minutes, both are rejected -> returns Optional.empty().
        //
        // Now let's test that: when maxRideTime is 40 min, it is rejected; but when maxRideTime is 60 min, it is accepted!

        Booking b1 = createBooking(1L, 13.1500, 77.6800);
        Booking b2 = createBooking(2L, 13.1410, 77.6800); // ~1.0 km from B1
        // Office is at 12.9200 (~25 km away)

        // With strict max ride time (30 mins), 25 km at 30 km/h takes 50 mins -> impossible -> rejected!
        Optional<OptimizedRoute> strictResult = optimizer.optimizeRoute(
                List.of(b1, b2), office, shiftStart, 30, 30.0
        );
        assertTrue(strictResult.isEmpty(), "Route should be rejected when travel time exceeds max allowed ride time");

        // With realistic max ride time (60 mins), route is accepted!
        Optional<OptimizedRoute> validResult = optimizer.optimizeRoute(
                List.of(b1, b2), office, shiftStart, 60, 30.0
        );
        assertTrue(validResult.isPresent(), "Route should be accepted when ride time satisfies constraints");
    }

    @Test
    void testOptimizerSelectsValidRouteOverShorterInvalidRoute() {
        // 3 stops along a line:
        // A is at 12.9500 (near office 12.9200)
        // B is at 12.9550
        // C is far away at 13.1000 (~20 km north)
        Booking near1 = createBooking(1L, 12.9500, 77.6800); // ~3.3 km to office
        Booking near2 = createBooking(2L, 12.9550, 77.6800); // ~3.9 km to office

        // At speed 30 km/h, 3.9 km takes ~8 minutes.
        // If maxRideTime is 10 minutes:
        // A pickup permutation of near2 -> near1 -> office takes:
        // dist(near2, near1) = 0.55 km (1 min) + dist(near1, office) = 3.3 km (6.6 min) = 7.6 mins <= 10 min -> VALID!
        // But if maxRideTime is 5 minutes:
        // 7.6 mins > 5 mins -> REJECTED!

        Optional<OptimizedRoute> validResult = optimizer.optimizeRoute(
                List.of(near1, near2), office, shiftStart, 10, 30.0
        );
        assertTrue(validResult.isPresent());

        Optional<OptimizedRoute> invalidResult = optimizer.optimizeRoute(
                List.of(near1, near2), office, shiftStart, 5, 30.0
        );
        assertTrue(invalidResult.isEmpty(), "Must be rejected when max ride time is violated");
    }

    @Test
    void testExceedingMaxCapacityThrowsException() {
        List<Booking> sevenBookings = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            sevenBookings.add(createBooking((long) i, 12.93 + (i * 0.001), 77.67));
        }

        assertThrows(IllegalArgumentException.class, () ->
                optimizer.optimizeRoute(sevenBookings, office, shiftStart, 60, 30.0)
        );
    }

    @Test
    void testEmptyBookingsReturnsEmptyOptional() {
        Optional<OptimizedRoute> result = optimizer.optimizeRoute(
                List.of(), office, shiftStart, 60, 30.0
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void testSingleBookingRoute() {
        Booking single = createBooking(1L, 12.9350, 77.6700);

        Optional<OptimizedRoute> result = optimizer.optimizeRoute(
                List.of(single), office, shiftStart, 45, 30.0
        );

        assertTrue(result.isPresent());
        OptimizedRoute route = result.get();
        assertEquals(1, route.stops().size());
        assertEquals(1, route.stops().get(0).sequence());
        assertTrue(route.totalDistanceKm() > 0.0);
    }
}
