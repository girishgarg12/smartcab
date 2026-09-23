package com.smartcab.routing.clustering;

import com.smartcab.entity.Booking;
import com.smartcab.entity.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridBasedEmployeeClustererTest {

    private EmployeeClusterer clusterer;

    @BeforeEach
    void setUp() {
        clusterer = new GridBasedEmployeeClusterer();
    }

    private Booking createBooking(Long id, double lat, double lon) {
        return Booking.builder()
                .id(id)
                .pickupLatitude(lat)
                .pickupLongitude(lon)
                .shiftStartTime(LocalDateTime.now())
                .status(BookingStatus.BOOKED)
                .build();
    }

    @Test
    void testEmptyInputReturnsEmptyList() {
        List<List<Booking>> result = clusterer.clusterBookings(List.of(), 4, 5.0);
        assertNotNull(result);
        assertTrue(result.isEmpty());

        List<List<Booking>> nullResult = clusterer.clusterBookings(null, 4, 5.0);
        assertNotNull(nullResult);
        assertTrue(nullResult.isEmpty());
    }

    @Test
    void testFourEmployeesWithCapacityFour() {
        // 4 employees clustered within ~500m of each other in Koramangala, Bangalore
        List<Booking> bookings = List.of(
                createBooking(1L, 12.9352, 77.6245),
                createBooking(2L, 12.9355, 77.6248),
                createBooking(3L, 12.9350, 77.6240),
                createBooking(4L, 12.9360, 77.6250)
        );

        List<List<Booking>> clusters = clusterer.clusterBookings(bookings, 4, 3.0);

        assertEquals(1, clusters.size(), "All 4 nearby employees should be grouped into a single cab cluster");
        assertEquals(4, clusters.get(0).size());
    }

    @Test
    void testFiveEmployeesWithCapacityFour() {
        // 5 nearby employees
        List<Booking> bookings = List.of(
                createBooking(1L, 12.9352, 77.6245),
                createBooking(2L, 12.9355, 77.6248),
                createBooking(3L, 12.9350, 77.6240),
                createBooking(4L, 12.9360, 77.6250),
                createBooking(5L, 12.9365, 77.6255)
        );

        List<List<Booking>> clusters = clusterer.clusterBookings(bookings, 4, 3.0);

        assertEquals(2, clusters.size(), "5 employees with capacity 4 must be partitioned into 2 clusters");
        assertEquals(4, clusters.get(0).size(), "First cab must have exactly capacity 4");
        assertEquals(1, clusters.get(1).size(), "Second cab must have the remaining 1 employee");

        // Verify total passenger count
        int totalPassengers = clusters.stream().mapToInt(List::size).sum();
        assertEquals(5, totalPassengers);
    }

    @Test
    void testDistantEmployeesNotGroupedUnnecessarily() {
        // Employee 1 & 2 in South Bangalore (Koramangala, ~12.93, 77.62)
        // Employee 3 & 4 in North Bangalore (Yelahanka, ~13.10, 77.59) - ~18 km away
        List<Booking> bookings = List.of(
                createBooking(1L, 12.9352, 77.6245),
                createBooking(2L, 12.9355, 77.6248),
                createBooking(3L, 13.1000, 77.5900),
                createBooking(4L, 13.1010, 77.5910)
        );

        // Max detour 3.0 km
        List<List<Booking>> clusters = clusterer.clusterBookings(bookings, 4, 3.0);

        assertEquals(2, clusters.size(), "Distant groups separated by 18 km should form 2 distinct clusters");

        List<Long> cluster1Ids = clusters.get(0).stream().map(Booking::getId).toList();
        List<Long> cluster2Ids = clusters.get(1).stream().map(Booking::getId).toList();

        assertTrue(cluster1Ids.containsAll(List.of(1L, 2L)) || cluster1Ids.containsAll(List.of(3L, 4L)));
        assertTrue(cluster2Ids.containsAll(List.of(1L, 2L)) || cluster2Ids.containsAll(List.of(3L, 4L)));
    }

    @Test
    void testCapacityConstraintNeverExceeded() {
        int capacity = 4;
        List<Booking> bookings = new ArrayList<>();
        // Generate 15 closely spaced bookings
        for (int i = 1; i <= 15; i++) {
            bookings.add(createBooking((long) i, 12.9350 + (i * 0.0005), 77.6240 + (i * 0.0005)));
        }

        List<List<Booking>> clusters = clusterer.clusterBookings(bookings, capacity, 5.0);

        assertFalse(clusters.isEmpty());
        for (List<Booking> cluster : clusters) {
            assertTrue(cluster.size() <= capacity,
                    "Cluster size (" + cluster.size() + ") must never exceed cab capacity (" + capacity + ")");
            assertFalse(cluster.isEmpty(), "Cluster must not be empty");
        }

        int totalAssigned = clusters.stream().mapToInt(List::size).sum();
        assertEquals(15, totalAssigned, "All 15 bookings must be assigned to clusters");
    }

    @Test
    void testDeterministicOutput() {
        List<Booking> bookings = List.of(
                createBooking(3L, 12.9350, 77.6240),
                createBooking(1L, 12.9352, 77.6245),
                createBooking(4L, 12.9360, 77.6250),
                createBooking(2L, 12.9355, 77.6248)
        );

        List<List<Booking>> run1 = clusterer.clusterBookings(bookings, 4, 3.0);
        List<List<Booking>> run2 = clusterer.clusterBookings(bookings, 4, 3.0);

        assertEquals(run1.size(), run2.size());
        for (int i = 0; i < run1.size(); i++) {
            List<Long> ids1 = run1.get(i).stream().map(Booking::getId).toList();
            List<Long> ids2 = run2.get(i).stream().map(Booking::getId).toList();
            assertEquals(ids1, ids2, "Clustering should be deterministic for the same input");
        }
    }
}
