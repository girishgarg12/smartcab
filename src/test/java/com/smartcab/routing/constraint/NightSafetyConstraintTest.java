package com.smartcab.routing.constraint;

import com.smartcab.entity.Booking;
import com.smartcab.entity.Gender;
import com.smartcab.entity.Office;
import com.smartcab.entity.User;
import com.smartcab.routing.optimizer.ExactPickupRouteOptimizer;
import com.smartcab.routing.optimizer.OptimizedRoute;
import com.smartcab.routing.optimizer.OptimizedStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NightSafetyConstraintTest {

    private NightSafetyConstraint constraint;
    private ExactPickupRouteOptimizer optimizer;
    private Office office;
    private LocalDateTime nightShiftStart;
    private LocalDateTime dayShiftStart;

    private User femaleUser1;
    private User femaleUser2;
    private User maleUser;

    @BeforeEach
    void setUp() {
        constraint = new NightSafetyConstraint();
        optimizer = new ExactPickupRouteOptimizer();

        office = Office.builder()
                .id(1L)
                .name("HQ Tech Hub")
                .latitude(12.9200)
                .longitude(77.6800)
                .build();

        // 22:00 (10:00 PM) is inside the default 20:00 to 06:00 night window
        nightShiftStart = LocalDateTime.of(2026, 10, 1, 22, 0, 0);

        // 14:00 (2:00 PM) is outside night hours (daytime)
        dayShiftStart = LocalDateTime.of(2026, 10, 1, 14, 0, 0);

        femaleUser1 = User.builder().id(101L).name("Alice Smith").gender(Gender.FEMALE).build();
        femaleUser2 = User.builder().id(102L).name("Carol White").gender(Gender.FEMALE).build();
        maleUser = User.builder().id(103L).name("Bob Jones").gender(Gender.MALE).build();
    }

    private Booking createBooking(Long id, User user, double lat, double lon, LocalDateTime shift) {
        return Booking.builder()
                .id(id)
                .user(user)
                .pickupLatitude(lat)
                .pickupLongitude(lon)
                .shiftStartTime(shift)
                .build();
    }

    private RouteCandidate buildCandidate(
            List<Booking> bookings,
            List<OptimizedStop> stops,
            LocalDateTime shiftTime,
            boolean hasGuard,
            int capacity) {
        return new RouteCandidate(
                bookings,
                stops,
                capacity,
                office,
                shiftTime,
                shiftTime,
                60,
                hasGuard
        );
    }

    @Test
    void testSafeRouteMaleFirstAtNight() {
        Booking bMale = createBooking(1L, maleUser, 12.9300, 77.6720, nightShiftStart);
        Booking bFemale = createBooking(2L, femaleUser1, 12.9350, 77.6700, nightShiftStart);

        List<OptimizedStop> stops = List.of(
                new OptimizedStop(bMale, 1, nightShiftStart.minusMinutes(30), 30, 12.9300, 77.6720),
                new OptimizedStop(bFemale, 2, nightShiftStart.minusMinutes(15), 15, 12.9350, 77.6700)
        );

        RouteCandidate candidate = buildCandidate(List.of(bMale, bFemale), stops, nightShiftStart, false, 4);

        ConstraintValidationResult result = constraint.validate(candidate);
        assertTrue(result.isValid(), "Route with male employee as first pickup at night should be valid without guard");
    }

    @Test
    void testSafeRouteDaytimeTrip() {
        Booking bFemale = createBooking(1L, femaleUser1, 12.9350, 77.6700, dayShiftStart);

        List<OptimizedStop> stops = List.of(
                new OptimizedStop(bFemale, 1, dayShiftStart.minusMinutes(20), 20, 12.9350, 77.6700)
        );

        RouteCandidate candidate = buildCandidate(List.of(bFemale), stops, dayShiftStart, false, 4);

        ConstraintValidationResult result = constraint.validate(candidate);
        assertTrue(result.isValid(), "Female employee alone during daytime hours should be valid");
    }

    @Test
    void testSafeRouteFemaleFirstWithEscortGuard() {
        Booking bFemale = createBooking(1L, femaleUser1, 12.9350, 77.6700, nightShiftStart);

        List<OptimizedStop> stops = List.of(
                new OptimizedStop(bFemale, 1, nightShiftStart.minusMinutes(20), 20, 12.9350, 77.6700)
        );

        // hasEscortGuard = true
        RouteCandidate candidate = buildCandidate(List.of(bFemale), stops, nightShiftStart, true, 4);

        ConstraintValidationResult result = constraint.validate(candidate);
        assertTrue(result.isValid(), "Female employee first pickup at night WITH escort guard should be valid");
    }

    @Test
    void testUnsafeRouteFemaleFirstAloneAtNightWithoutGuard() {
        Booking bFemale = createBooking(1L, femaleUser1, 12.9350, 77.6700, nightShiftStart);

        List<OptimizedStop> stops = List.of(
                new OptimizedStop(bFemale, 1, nightShiftStart.minusMinutes(25), 25, 12.9350, 77.6700)
        );

        // hasEscortGuard = false
        RouteCandidate candidate = buildCandidate(List.of(bFemale), stops, nightShiftStart, false, 4);

        ConstraintValidationResult result = constraint.validate(candidate);
        assertFalse(result.isValid(), "Female employee first pickup at night without guard MUST be invalid");
        assertEquals("NightSafetyConstraint", result.constraintName());
        assertTrue(result.failureReason().contains("Night safety violation"));
        assertTrue(result.failureReason().contains("Alice Smith"));
    }

    @Test
    void testReorderedSafeRoute() {
        // Two bookings: one female, one male
        // If female is picked up first, it's unsafe.
        // The optimizer must evaluate all permutations and choose [Male, Female] without needing an escort guard.
        Booking bFemale = createBooking(1L, femaleUser1, 12.9350, 77.6700, nightShiftStart);
        Booking bMale = createBooking(2L, maleUser, 12.9300, 77.6720, nightShiftStart);

        Optional<OptimizedRoute> routeOpt = optimizer.optimizeRoute(
                List.of(bFemale, bMale), office, nightShiftStart, 45, 30.0, 4
        );

        assertTrue(routeOpt.isPresent(), "Optimizer should find a safe ordering");
        OptimizedRoute route = routeOpt.get();

        // The first stop must be the male employee!
        assertEquals(2, route.stops().size());
        assertEquals(maleUser.getId(), route.stops().get(0).booking().getUser().getId(),
                "Male employee must be sequenced as first pickup to protect female passenger");
        assertEquals(femaleUser1.getId(), route.stops().get(1).booking().getUser().getId(),
                "Female employee should be picked up second (not alone)");
        assertFalse(route.requiresEscortGuard(), "Reordered route should not require an escort guard");
    }

    @Test
    void testImpossibleSafeRouteCabFullOfFemalesAtNight() {
        // 4 female bookings in a 4-seater cab at night
        // Every permutation starts with a female passenger.
        // Adding an escort guard requires 4 + 1 = 5 seats > cab capacity (4).
        // Result must be Optional.empty() - never silently violate safety!
        User f3 = User.builder().id(103L).name("Diana Prince").gender(Gender.FEMALE).build();
        User f4 = User.builder().id(104L).name("Eva Green").gender(Gender.FEMALE).build();

        Booking b1 = createBooking(1L, femaleUser1, 12.9350, 77.6700, nightShiftStart);
        Booking b2 = createBooking(2L, femaleUser2, 12.9300, 77.6720, nightShiftStart);
        Booking b3 = createBooking(3L, f3, 12.9250, 77.6750, nightShiftStart);
        Booking b4 = createBooking(4L, f4, 12.9220, 77.6780, nightShiftStart);

        Optional<OptimizedRoute> routeOpt = optimizer.optimizeRoute(
                List.of(b1, b2, b3, b4), office, nightShiftStart, 60, 30.0, 4
        );

        assertTrue(routeOpt.isEmpty(),
                "Route must be rejected as impossible when no safe reordering exists and no seat is available for an escort guard");
    }

    @Test
    void testEscortedRouteWhenReorderingImpossibleAndSeatAvailable() {
        // 2 female bookings in a 4-seater cab at night
        // Reordering cannot avoid female first pickup (all passengers female).
        // But cab capacity is 4, so 2 passengers + 1 guard = 3 <= 4 seats!
        // Optimizer should safely allocate an escort guard.
        Booking b1 = createBooking(1L, femaleUser1, 12.9350, 77.6700, nightShiftStart);
        Booking b2 = createBooking(2L, femaleUser2, 12.9300, 77.6720, nightShiftStart);

        Optional<OptimizedRoute> routeOpt = optimizer.optimizeRoute(
                List.of(b1, b2), office, nightShiftStart, 60, 30.0, 4
        );

        assertTrue(routeOpt.isPresent(), "Optimizer should succeed by assigning an escort guard");
        OptimizedRoute route = routeOpt.get();
        assertTrue(route.requiresEscortGuard(), "Route must report that an escort guard is required");
        assertEquals(2, route.stops().size());
    }

    @Test
    void testCustomNightHoursWindow() {
        // Configure custom night hours: 22:00 to 05:00
        NightSafetyConstraint customConstraint = new NightSafetyConstraint(
                LocalTime.of(22, 0), LocalTime.of(5, 0)
        );

        // 21:30 is daytime under this custom window
        assertFalse(customConstraint.isNightTime(LocalTime.of(21, 30)));
        // 22:00 is night time
        assertTrue(customConstraint.isNightTime(LocalTime.of(22, 0)));
        // 03:00 is night time
        assertTrue(customConstraint.isNightTime(LocalTime.of(3, 0)));
        // 05:00 is night time (inclusive)
        assertTrue(customConstraint.isNightTime(LocalTime.of(5, 0)));
        // 05:01 is daytime
        assertFalse(customConstraint.isNightTime(LocalTime.of(5, 1)));
    }
}
