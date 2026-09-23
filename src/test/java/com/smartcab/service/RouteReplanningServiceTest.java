package com.smartcab.service;

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
class RouteReplanningServiceTest {

    @Autowired
    private RouteReplanningService routeReplanningService;

    @Autowired
    private BookingService bookingService;

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
    private LocalDateTime nightShift;

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
        nightShift = LocalDateTime.of(2026, 10, 1, 22, 0, 0);
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
    void testCancelledEmployeeDisappearsFromRoute() {
        User u1 = createUser("Employee 1", "e1@example.com", Gender.MALE);
        User u2 = createUser("Employee 2", "e2@example.com", Gender.MALE);
        User u3 = createUser("Employee 3", "e3@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 12.9400, 77.6700, dayShift, BookingStatus.ASSIGNED);
        Booking b2 = createBooking(u2, 12.9300, 77.6720, dayShift, BookingStatus.ASSIGNED);
        Booking b3 = createBooking(u3, 12.9250, 77.6750, dayShift, BookingStatus.ASSIGNED);

        Cab cab = createCab("KA-01-CAB-1", 4, CabStatus.ASSIGNED);
        Route route = createRouteWithStops(cab, office, dayShift, List.of(b1, b2, b3));

        assertEquals(3, routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId()).size());

        // Cancel employee 2 via booking service
        bookingService.cancelBooking(b2.getId(), u2.getEmail());

        // Verify booking 2 is CANCELLED
        Booking updatedB2 = bookingRepository.findById(b2.getId()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, updatedB2.getStatus());

        // Verify cancelled employee stop disappeared from route
        List<RouteStop> updatedStops = routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId());
        assertEquals(2, updatedStops.size(), "Route must contain only the 2 remaining employees");

        List<Long> remainingBookingIds = updatedStops.stream().map(s -> s.getBooking().getId()).toList();
        assertTrue(remainingBookingIds.contains(b1.getId()));
        assertTrue(remainingBookingIds.contains(b3.getId()));
        assertFalse(remainingBookingIds.contains(b2.getId()), "Cancelled booking must not be in route stops");

        // Verify sequences are 1-indexed and contiguous
        assertEquals(1, updatedStops.get(0).getSequence());
        assertEquals(2, updatedStops.get(1).getSequence());
    }

    @Test
    void testRemainingEmployeesReceiveUpdatedEtas() {
        // Stop 1: furthest north (12.9500)
        // Stop 2: detour stop (12.9400, 77.6600)
        // Stop 3: near office (12.9250, 77.6780)
        // Office: (12.9200, 77.6800)
        User u1 = createUser("Employee 1", "eta1@example.com", Gender.MALE);
        User u2 = createUser("Employee 2", "eta2@example.com", Gender.MALE);
        User u3 = createUser("Employee 3", "eta3@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 12.9500, 77.6700, dayShift, BookingStatus.ASSIGNED);
        Booking b2 = createBooking(u2, 12.9400, 77.6600, dayShift, BookingStatus.ASSIGNED);
        Booking b3 = createBooking(u3, 12.9250, 77.6780, dayShift, BookingStatus.ASSIGNED);

        Cab cab = createCab("KA-01-ETA-01", 4, CabStatus.ASSIGNED);
        Route route = createRouteWithStops(cab, office, dayShift, List.of(b1, b2, b3));

        // Get initial stop 1 ETA
        List<RouteStop> initialStops = routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId());
        LocalDateTime initialB1Eta = initialStops.get(0).getPickupEta();

        // Cancel middle detour passenger (Employee 2)
        routeReplanningService.replanRouteOnCancellation(b2);

        // Fetch updated stops
        List<RouteStop> updatedStops = routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId());
        assertEquals(2, updatedStops.size());

        RouteStop newB1Stop = updatedStops.stream()
                .filter(s -> s.getBooking().getId().equals(b1.getId()))
                .findFirst().orElseThrow();

        // Without the detour to stop 2, trip from B1 to office is more direct:
        // ETAs are recalculated and valid
        assertNotNull(newB1Stop.getPickupEta());
        assertTrue(newB1Stop.getPickupEta().isBefore(dayShift));
        assertTrue(newB1Stop.getRideDuration() > 0);

        Route updatedRoute = routeRepository.findById(route.getId()).orElseThrow();
        assertTrue(updatedRoute.getTotalDistance() > 0);
    }

    @Test
    void testUnrelatedRouteRemainsUnchanged() {
        // Cab 1 / Route 1
        User u1 = createUser("User R1", "r1@example.com", Gender.MALE);
        User u2 = createUser("User R2", "r2@example.com", Gender.MALE);
        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.ASSIGNED);
        Booking b2 = createBooking(u2, 12.9300, 77.6720, dayShift, BookingStatus.ASSIGNED);
        Cab cab1 = createCab("KA-01-CAB-1", 4, CabStatus.ASSIGNED);
        Route route1 = createRouteWithStops(cab1, office, dayShift, List.of(b1, b2));

        // Cab 2 / Route 2 (Unrelated route)
        User u3 = createUser("User R3", "r3@example.com", Gender.MALE);
        User u4 = createUser("User R4", "r4@example.com", Gender.MALE);
        Booking b3 = createBooking(u3, 12.9280, 77.6740, dayShift, BookingStatus.ASSIGNED);
        Booking b4 = createBooking(u4, 12.9250, 77.6760, dayShift, BookingStatus.ASSIGNED);
        Cab cab2 = createCab("KA-01-CAB-2", 4, CabStatus.ASSIGNED);
        Route route2 = createRouteWithStops(cab2, office, dayShift, List.of(b3, b4));

        // Snapshot Route 2 data before cancelling b1 on Route 1
        List<RouteStop> route2StopsBefore = routeStopRepository.findByRouteIdOrderBySequenceAsc(route2.getId());
        double route2DistBefore = route2.getTotalDistance();
        LocalDateTime route2EtaBefore = route2.getEstimatedArrivalTime();

        // Cancel b1 on Route 1
        bookingService.cancelBooking(b1.getId(), u1.getEmail());

        // Verify Route 1 has only 1 stop remaining
        List<RouteStop> route1StopsAfter = routeStopRepository.findByRouteIdOrderBySequenceAsc(route1.getId());
        assertEquals(1, route1StopsAfter.size());
        assertEquals(b2.getId(), route1StopsAfter.get(0).getBooking().getId());

        // Verify Route 2 is 100% UNCHANGED
        Route route2After = routeRepository.findById(route2.getId()).orElseThrow();
        assertEquals(route2DistBefore, route2After.getTotalDistance());
        assertEquals(route2EtaBefore, route2After.getEstimatedArrivalTime());
        assertEquals(CabStatus.ASSIGNED, cabRepository.findById(cab2.getId()).orElseThrow().getStatus());

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

    @Test
    void testCancellationFailureDoesNotLeavePartialDatabaseChanges() {
        // Construct a scenario where the remaining employee is ~48 km away (13.3500 vs office 12.9200)
        // At 30 km/h, 48 km takes 96 minutes > default max ride time (60 min).
        // While paired with dummy data initially, once the other cancels, the lone passenger violates MaxRideTimeConstraint!
        User uFar = createUser("Far Away Employee", "faraway@example.com", Gender.MALE);
        User uNormal = createUser("Normal Employee", "normal@example.com", Gender.MALE);

        Booking bFar = createBooking(uFar, 13.3500, 77.6800, dayShift, BookingStatus.ASSIGNED);
        Booking bNormal = createBooking(uNormal, 12.9300, 77.6720, dayShift, BookingStatus.ASSIGNED);

        Cab cab = createCab("KA-01-FAIL-01", 4, CabStatus.ASSIGNED);
        Route route = createRouteWithStops(cab, office, dayShift, List.of(bFar, bNormal));

        // When bNormal cancels, bFar alone violates the 60-min max ride time constraint.
        // Route replanning throws IllegalStateException, rolling back the transaction.
        assertThrows(IllegalStateException.class, () ->
                bookingService.cancelBooking(bNormal.getId(), uNormal.getEmail())
        );

        // Verify transaction rollback:
        // 1. bNormal status is still ASSIGNED (not CANCELLED)
        Booking bNormalAfter = bookingRepository.findById(bNormal.getId()).orElseThrow();
        assertEquals(BookingStatus.ASSIGNED, bNormalAfter.getStatus(),
                "Booking status must not be modified when replanning fails");

        // 2. Both route stops still exist in the database
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId());
        assertEquals(2, stops.size(), "Original route stops must remain intact on rollback");

        // 3. Route is still CONFIRMED
        Route routeAfter = routeRepository.findById(route.getId()).orElseThrow();
        assertEquals(RouteStatus.CONFIRMED, routeAfter.getStatus());
    }

    @Test
    void testAllPassengersCancelledReleasesCab() {
        User u1 = createUser("Sole Employee", "sole@example.com", Gender.MALE);
        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.ASSIGNED);

        Cab cab = createCab("KA-01-SOLO-01", 4, CabStatus.ASSIGNED);
        Route route = createRouteWithStops(cab, office, dayShift, List.of(b1));

        // Sole passenger cancels
        bookingService.cancelBooking(b1.getId(), u1.getEmail());

        // Verify booking is CANCELLED
        assertEquals(BookingStatus.CANCELLED, bookingRepository.findById(b1.getId()).orElseThrow().getStatus());

        // Verify route is CANCELLED and has 0 stops
        Route updatedRoute = routeRepository.findById(route.getId()).orElseThrow();
        assertEquals(RouteStatus.CANCELLED, updatedRoute.getStatus());
        assertEquals(0, routeStopRepository.findByRouteIdOrderBySequenceAsc(route.getId()).size());

        // Verify cab is released to AVAILABLE
        Cab updatedCab = cabRepository.findById(cab.getId()).orElseThrow();
        assertEquals(CabStatus.AVAILABLE, updatedCab.getStatus(), "Cab must be returned to AVAILABLE status");
    }
}
