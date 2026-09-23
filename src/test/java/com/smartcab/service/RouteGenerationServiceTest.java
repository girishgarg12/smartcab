package com.smartcab.service;

import com.smartcab.dto.RouteGenerationRequest;
import com.smartcab.dto.RouteResponse;
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
class RouteGenerationServiceTest {

    @Autowired
    private RouteGenerationService routeGenerationService;

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

    @Test
    void testSuccessfulRouteGeneration() {
        User u1 = createUser("Alice Smith", "alice@example.com", Gender.FEMALE);
        User u2 = createUser("Bob Jones", "bob@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.BOOKED);
        Booking b2 = createBooking(u2, 12.9300, 77.6720, dayShift, BookingStatus.BOOKED);

        Cab cab = createCab("KA-01-AB-1234", 4, CabStatus.AVAILABLE);

        List<RouteResponse> routes = routeGenerationService.generateRoutes(null);

        assertEquals(1, routes.size(), "Should generate exactly 1 route");
        RouteResponse route = routes.get(0);

        assertEquals("KA-01-AB-1234", route.getCab().getVehicleNumber());
        assertEquals(CabStatus.ASSIGNED, route.getCab().getStatus());
        assertEquals(office.getId(), route.getOffice().getId());
        assertEquals(dayShift, route.getShiftStartTime());
        assertEquals(RouteStatus.CONFIRMED, route.getStatus());
        assertTrue(route.getTotalDistanceKm() > 0.0);
        assertNotNull(route.getEstimatedArrivalTime());
        assertEquals(2, route.getStops().size());

        // Verify sequence ordering & ETAs
        assertEquals(1, route.getStops().get(0).getSequence());
        assertEquals(2, route.getStops().get(1).getSequence());
        assertNotNull(route.getStops().get(0).getPickupEta());
        assertNotNull(route.getStops().get(1).getPickupEta());

        // Verify persistence & status updates in DB
        assertEquals(1, routeRepository.count());
        assertEquals(2, routeStopRepository.count());

        Booking updatedB1 = bookingRepository.findById(b1.getId()).orElseThrow();
        Booking updatedB2 = bookingRepository.findById(b2.getId()).orElseThrow();
        assertEquals(BookingStatus.ASSIGNED, updatedB1.getStatus(), "Booking 1 status must be updated to ASSIGNED");
        assertEquals(BookingStatus.ASSIGNED, updatedB2.getStatus(), "Booking 2 status must be updated to ASSIGNED");

        Cab updatedCab = cabRepository.findById(cab.getId()).orElseThrow();
        assertEquals(CabStatus.ASSIGNED, updatedCab.getStatus(), "Cab status must be updated to ASSIGNED");
    }

    @Test
    void testCapacityConstraint() {
        // 3 bookings, but only 1 cab with capacity 2 is available
        User u1 = createUser("User 1", "u1@example.com", Gender.MALE);
        User u2 = createUser("User 2", "u2@example.com", Gender.MALE);
        User u3 = createUser("User 3", "u3@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.BOOKED);
        Booking b2 = createBooking(u2, 12.9300, 77.6720, dayShift, BookingStatus.BOOKED);
        Booking b3 = createBooking(u3, 12.9280, 77.6740, dayShift, BookingStatus.BOOKED);

        Cab smallCab = createCab("KA-01-MINI-01", 2, CabStatus.AVAILABLE);

        // When cluster size is 3 and available cab only has capacity 2, cab cannot accept the cluster
        List<RouteResponse> routes = routeGenerationService.generateRoutes(null);

        assertTrue(routes.isEmpty(), "Route should not be generated when cab capacity is smaller than cluster size");
        assertEquals(0, routeRepository.count());
        assertEquals(0, routeStopRepository.count());

        // Bookings remain BOOKED and cab remains AVAILABLE
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(b1.getId()).orElseThrow().getStatus());
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(b2.getId()).orElseThrow().getStatus());
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(b3.getId()).orElseThrow().getStatus());
        assertEquals(CabStatus.AVAILABLE, cabRepository.findById(smallCab.getId()).orElseThrow().getStatus());
    }

    @Test
    void testMaxRideConstraint() {
        // Bookings located far away (~37 km)
        User u1 = createUser("Far Employee", "far@example.com", Gender.MALE);
        User u2 = createUser("Far Employee 2", "far2@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 13.2500, 77.6800, dayShift, BookingStatus.BOOKED);
        Booking b2 = createBooking(u2, 13.2450, 77.6800, dayShift, BookingStatus.BOOKED);

        createCab("KA-01-SEDAN-01", 4, CabStatus.AVAILABLE);

        // Request with strict max ride time: 15 minutes at 30 km/h (impossible for 37 km journey)
        RouteGenerationRequest request = RouteGenerationRequest.builder()
                .maxRideTimeMinutes(15)
                .averageSpeedKmh(30.0)
                .build();

        List<RouteResponse> routes = routeGenerationService.generateRoutes(request);

        assertTrue(routes.isEmpty(), "Must reject routes where ride duration exceeds maxRideTimeMinutes");
        assertEquals(0, routeRepository.count());
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(b1.getId()).orElseThrow().getStatus());
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(b2.getId()).orElseThrow().getStatus());
    }

    @Test
    void testNoValidRouteDueToNightSafetyImpossible() {
        // 4 female bookings for a 22:00 night shift in a 4-seater cab
        // Night safety: female cannot be first pickup alone without guard.
        // Guard cannot fit because 4 passengers + 1 guard = 5 > 4 (capacity exceeded).
        User f1 = createUser("Alice", "alice_night@example.com", Gender.FEMALE);
        User f2 = createUser("Beth", "beth_night@example.com", Gender.FEMALE);
        User f3 = createUser("Carol", "carol_night@example.com", Gender.FEMALE);
        User f4 = createUser("Diana", "diana_night@example.com", Gender.FEMALE);

        Booking b1 = createBooking(f1, 12.9350, 77.6700, nightShift, BookingStatus.BOOKED);
        Booking b2 = createBooking(f2, 12.9300, 77.6720, nightShift, BookingStatus.BOOKED);
        Booking b3 = createBooking(f3, 12.9280, 77.6740, nightShift, BookingStatus.BOOKED);
        Booking b4 = createBooking(f4, 12.9250, 77.6760, nightShift, BookingStatus.BOOKED);

        createCab("KA-01-NIGHT-01", 4, CabStatus.AVAILABLE);

        List<RouteResponse> routes = routeGenerationService.generateRoutes(null);

        assertTrue(routes.isEmpty(), "Must reject impossible night route without silently violating safety");
        assertEquals(0, routeRepository.count());
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(b1.getId()).orElseThrow().getStatus());
    }

    @Test
    void testNoAvailableCab() {
        User u1 = createUser("User Single", "single@example.com", Gender.MALE);
        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.BOOKED);

        // All cabs are ASSIGNED or in MAINTENANCE (none AVAILABLE)
        createCab("KA-01-BUSY-01", 4, CabStatus.ASSIGNED);
        createCab("KA-01-GARAGE-01", 4, CabStatus.MAINTENANCE);

        List<RouteResponse> routes = routeGenerationService.generateRoutes(null);

        assertTrue(routes.isEmpty(), "No route should be generated when zero cabs are available");
        assertEquals(0, routeRepository.count());
        assertEquals(BookingStatus.BOOKED, bookingRepository.findById(b1.getId()).orElseThrow().getStatus());
    }

    @Test
    void testDoNotRegenerateAlreadyAssignedBookings() {
        User u1 = createUser("Already Assigned User", "assigned@example.com", Gender.MALE);
        User u2 = createUser("New User", "new@example.com", Gender.MALE);

        Booking b1 = createBooking(u1, 12.9350, 77.6700, dayShift, BookingStatus.ASSIGNED);
        Booking b2 = createBooking(u2, 12.9300, 77.6720, dayShift, BookingStatus.BOOKED);

        createCab("KA-01-FREE-01", 4, CabStatus.AVAILABLE);

        List<RouteResponse> routes = routeGenerationService.generateRoutes(null);

        assertEquals(1, routes.size());
        RouteResponse route = routes.get(0);
        assertEquals(1, route.getStops().size(), "Only the unassigned booking should be scheduled");
        assertEquals(b2.getId(), route.getStops().get(0).getBookingId());

        // Booking 1 remains untouched
        assertEquals(BookingStatus.ASSIGNED, bookingRepository.findById(b1.getId()).orElseThrow().getStatus());
    }
}
