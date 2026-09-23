package com.smartcab.service;

import com.smartcab.dto.LateBookingInsertionResult;
import com.smartcab.entity.*;
import com.smartcab.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LateBookingServiceTest {

    @Autowired
    private LateBookingService lateBookingService;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CabRepository cabRepository;

    @Autowired
    private OfficeRepository officeRepository;

    @Autowired
    private UserRepository userRepository;

    private Office office;
    private LocalDateTime dayShift;

    @BeforeEach
    void setUp() {
        routeStopRepository.deleteAll();
        routeRepository.deleteAll();
        bookingRepository.deleteAll();
        cabRepository.deleteAll();
        officeRepository.deleteAll();
        userRepository.deleteAll();

        office = officeRepository.save(Office.builder()
                .name("HQ Tech Hub")
                .address("Outer Ring Road, Bangalore")
                .latitude(12.9200)
                .longitude(77.6800)
                .build());

        dayShift = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
    }

    private User createUser(String name, String email, Gender gender) {
        return userRepository.save(User.builder()
                .name(name)
                .email(email)
                .passwordHash("secret123")
                .role(Role.EMPLOYEE)
                .gender(gender)
                .active(true)
                .build());
    }

    private Cab createCab(String vehicleNumber, int capacity, CabStatus status) {
        return cabRepository.save(Cab.builder()
                .vehicleNumber(vehicleNumber)
                .capacity(capacity)
                .status(status)
                .build());
    }

    private Booking createBooking(User user, double lat, double lon, LocalDateTime shift, BookingStatus status) {
        return bookingRepository.save(Booking.builder()
                .user(user)
                .office(office)
                .pickupLatitude(lat)
                .pickupLongitude(lon)
                .shiftStartTime(shift)
                .status(status)
                .idempotencyKey("KEY-" + System.nanoTime() + "-" + user.getId())
                .build());
    }

    private Route createRouteWithStops(Cab cab, Office office, LocalDateTime shift, List<Booking> bookings) {
        Route route = routeRepository.save(Route.builder()
                .cab(cab)
                .office(office)
                .shiftStartTime(shift)
                .status(RouteStatus.CONFIRMED)
                .totalDistance(10.0)
                .estimatedArrivalTime(shift)
                .build());

        for (int i = 0; i < bookings.size(); i++) {
            Booking b = bookings.get(i);
            routeStopRepository.save(RouteStop.builder()
                    .route(route)
                    .booking(b)
                    .sequence(i + 1)
                    .pickupEta(shift.minusMinutes(30 - (i * 10)))
                    .rideDuration(30 - (i * 10))
                    .latitude(b.getPickupLatitude())
                    .longitude(b.getPickupLongitude())
                    .build());
        }

        return route;
    }

    @Test
    void testSuccessfulInsertion() {
        // Existing route on a 4-seater cab with 2 passengers
        User u1 = createUser("Alice", "alice_late@example.com", Gender.MALE);
        User u2 = createUser("Bob", "bob_late@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.ASSIGNED);
        Booking b2 = createBooking(u2, 12.9300, 77.6720, dayShift, BookingStatus.ASSIGNED);

        Cab cab = createCab("KA-01-LATE-01", 4, CabStatus.ASSIGNED);
        Route route = createRouteWithStops(cab, office, dayShift, List.of(b1, b2));

        // New late booking arrives nearby
        User uLate = createUser("Charlie", "charlie_late@example.com", Gender.MALE);
        Booking bLate = createBooking(uLate, 12.9280, 77.6740, dayShift, BookingStatus.BOOKED);

        LateBookingInsertionResult result = lateBookingService.tryInsertLateBooking(bLate.getId());

        assertTrue(result.isInserted(), "Late booking should be successfully inserted");
        assertEquals(route.getId(), result.getRouteId());
        assertEquals(cab.getVehicleNumber(), result.getVehicleNumber());
        assertNotNull(result.getUpdatedRoute());

        // Verify booking status updated to ASSIGNED
        Booking updatedBLate = bookingRepository.findById(bLate.getId()).orElseThrow();
        assertEquals(BookingStatus.ASSIGNED, updatedBLate.getStatus());

        // Verify route now has 3 stops including the late booking
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId());
        assertEquals(3, stops.size());

        boolean containsLateBooking = stops.stream()
                .anyMatch(s -> s.getBooking().getId().equals(bLate.getId()));
        assertTrue(containsLateBooking, "Route stops must include the newly inserted late booking");
    }

    @Test
    void testCapacityRejection() {
        // Cab is at full capacity (4 passengers in capacity 4)
        User u1 = createUser("User 1", "u1_cap@example.com", Gender.MALE);
        User u2 = createUser("User 2", "u2_cap@example.com", Gender.MALE);
        User u3 = createUser("User 3", "u3_cap@example.com", Gender.MALE);
        User u4 = createUser("User 4", "u4_cap@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.ASSIGNED);
        Booking b2 = createBooking(u2, 12.9300, 77.6720, dayShift, BookingStatus.ASSIGNED);
        Booking b3 = createBooking(u3, 12.9280, 77.6740, dayShift, BookingStatus.ASSIGNED);
        Booking b4 = createBooking(u4, 12.9250, 77.6760, dayShift, BookingStatus.ASSIGNED);

        Cab fullCab = createCab("KA-01-FULL-01", 4, CabStatus.ASSIGNED);
        Route route = createRouteWithStops(fullCab, office, dayShift, List.of(b1, b2, b3, b4));

        // Late booking arrives
        User uLate = createUser("Late Passenger", "late_cap@example.com", Gender.MALE);
        Booking bLate = createBooking(uLate, 12.9270, 77.6750, dayShift, BookingStatus.BOOKED);

        LateBookingInsertionResult result = lateBookingService.tryInsertLateBooking(bLate.getId());

        assertFalse(result.isInserted(), "Must reject insertion when all cabs are at full capacity");
        assertTrue(result.getFailureReason().contains("maximum capacity"));

        // Booking remains BOOKED
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(bLate.getId()).orElseThrow().getStatus());

        // Route stops remain 4
        assertEquals(4, routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId()).size());
    }

    @Test
    void testMaxRideRejection() {
        // Route has 1 passenger with available seats in capacity 4
        User u1 = createUser("Local User", "local@example.com", Gender.MALE);
        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.ASSIGNED);

        Cab cab = createCab("KA-01-RIDE-01", 4, CabStatus.ASSIGNED);
        Route route = createRouteWithStops(cab, office, dayShift, List.of(b1));

        // Late booking located very far away (~53 km, 13.4000 vs 12.9200)
        // Travel time > 100 min, violating 60-min max ride time constraint
        User uFar = createUser("Far Passenger", "far_ride@example.com", Gender.MALE);
        Booking bFar = createBooking(uFar, 13.4000, 77.6800, dayShift, BookingStatus.BOOKED);

        LateBookingInsertionResult result = lateBookingService.tryInsertLateBooking(bFar.getId());

        assertFalse(result.isInserted(), "Must reject insertion when route constraints are violated");
        assertTrue(result.getFailureReason().contains("violating hard constraints"));

        // Booking remains BOOKED
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(bFar.getId()).orElseThrow().getStatus());

        // Route stops remain 1
        assertEquals(1, routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId()).size());
    }

    @Test
    void testUnrelatedRoutesRemainingUnchanged() {
        // Route 1 (Cab 1) with 1 passenger
        User u1 = createUser("User Cab1", "cab1_user@example.com", Gender.MALE);
        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.ASSIGNED);
        Cab cab1 = createCab("KA-01-CAB-11", 4, CabStatus.ASSIGNED);
        Route route1 = createRouteWithStops(cab1, office, dayShift, List.of(b1));

        // Route 2 (Cab 2) with 2 passengers
        User u2 = createUser("User Cab2 A", "cab2_user_a@example.com", Gender.MALE);
        User u3 = createUser("User Cab2 B", "cab2_user_b@example.com", Gender.MALE);
        Booking b2 = createBooking(u2, 12.9500, 77.6800, dayShift, BookingStatus.ASSIGNED);
        Booking b3 = createBooking(u3, 12.9450, 77.6800, dayShift, BookingStatus.ASSIGNED);
        Cab cab2 = createCab("KA-01-CAB-22", 4, CabStatus.ASSIGNED);
        Route route2 = createRouteWithStops(cab2, office, dayShift, List.of(b2, b3));

        // Snapshot Route 2 state
        List<RouteStop> route2StopsBefore = routeStopRepository.findByRouteIdOrderBySequenceAsc(route2.getId());
        double distBefore = route2.getTotalDistance();
        LocalDateTime etaBefore = route2.getEstimatedArrivalTime();

        // Late booking arrives next to Route 1 (12.9340, 77.6705)
        User uLate = createUser("Late Guy", "late_guy@example.com", Gender.MALE);
        Booking bLate = createBooking(uLate, 12.9340, 77.6705, dayShift, BookingStatus.BOOKED);

        LateBookingInsertionResult result = lateBookingService.tryInsertLateBooking(bLate.getId());

        assertTrue(result.isInserted());
        assertEquals(route1.getId(), result.getRouteId(), "Should insert into the closest cab (Route 1)");

        // Route 1 now has 2 stops
        assertEquals(2, routeStopRepository.findByRouteIdOrderBySequenceAsc(route1.getId()).size());

        // Route 2 is 100% UNCHANGED
        Route route2After = routeRepository.findById(route2.getId()).orElseThrow();
        assertEquals(distBefore, route2After.getTotalDistance());
        assertEquals(etaBefore, route2After.getEstimatedArrivalTime());

        List<RouteStop> route2StopsAfter = routeStopRepository.findByRouteIdOrderBySequenceAsc(route2.getId());
        assertEquals(route2StopsBefore.size(), route2StopsAfter.size());
        for (int i = 0; i < route2StopsBefore.size(); i++) {
            RouteStop before = route2StopsBefore.get(i);
            RouteStop after = route2StopsAfter.get(i);
            assertEquals(before.getId(), after.getId());
            assertEquals(before.getSequence(), after.getSequence());
            assertEquals(before.getPickupEta(), after.getPickupEta());
            assertEquals(before.getRideDuration(), after.getRideDuration());
            assertEquals(before.getBooking().getId(), after.getBooking().getId());
        }
    }
}
