package com.smartcab.routing.distance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HaversineDistanceCalculatorTest {

    private DistanceCalculator distanceCalculator;

    @BeforeEach
    void setUp() {
        distanceCalculator = new HaversineDistanceCalculator();
    }

    @Test
    void testSameCoordinatesReturnZeroDistance() {
        double lat = 12.9716;
        double lon = 77.5946;

        double distance = distanceCalculator.calculateDistanceKm(lat, lon, lat, lon);
        assertEquals(0.0, distance, 0.0001, "Distance between identical points should be 0.0 km");

        GeoLocation location = new GeoLocation(lat, lon);
        double objectDistance = distanceCalculator.calculateDistanceKm(location, location);
        assertEquals(0.0, objectDistance, 0.0001);
    }

    @Test
    void testKnownApproximateDistance() {
        // London: 51.5074° N, 0.1278° W
        double londonLat = 51.5074;
        double londonLon = -0.1278;

        // Paris: 48.8566° N, 2.3522° E
        double parisLat = 48.8566;
        double parisLon = 2.3522;

        // Known great circle distance between London and Paris is ~343.5 km
        double distance = distanceCalculator.calculateDistanceKm(londonLat, londonLon, parisLat, parisLon);
        assertEquals(343.5, distance, 2.0, "Distance between London and Paris should approximate ~343.5 km");

        // Bangalore Majestic to Kempegowda International Airport (BLR): ~28.5 km
        double majesticLat = 12.9767;
        double majesticLon = 77.5713;
        double blrAirportLat = 13.1986;
        double blrAirportLon = 77.7066;

        double blrDistance = distanceCalculator.calculateDistanceKm(majesticLat, majesticLon, blrAirportLat, blrAirportLon);
        assertEquals(28.5, blrDistance, 1.5, "Distance from Majestic to BLR Airport should approximate ~28.5 km");
    }

    @Test
    void testSymmetry() {
        double latA = 12.9716;
        double lonA = 77.5946;
        double latB = 13.0827;
        double lonB = 80.2707;

        double distAB = distanceCalculator.calculateDistanceKm(latA, lonA, latB, lonB);
        double distBA = distanceCalculator.calculateDistanceKm(latB, lonB, latA, lonA);

        assertEquals(distAB, distBA, 0.00001, "Distance from A to B must equal distance from B to A");
    }

    @Test
    void testInvalidLatitudeThrowsException() {
        // Latitude > 90
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                distanceCalculator.calculateDistanceKm(90.1, 77.0, 12.0, 77.0));
        assertTrue(ex1.getMessage().contains("Latitude must be between -90.0 and 90.0"));

        // Latitude < -90
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                distanceCalculator.calculateDistanceKm(12.0, 77.0, -90.5, 77.0));
        assertTrue(ex2.getMessage().contains("Latitude must be between -90.0 and 90.0"));
    }

    @Test
    void testInvalidLongitudeThrowsException() {
        // Longitude > 180
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                distanceCalculator.calculateDistanceKm(12.0, 180.1, 13.0, 77.0));
        assertTrue(ex1.getMessage().contains("Longitude must be between -180.0 and 180.0"));

        // Longitude < -180
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                distanceCalculator.calculateDistanceKm(12.0, 77.0, 13.0, -180.5));
        assertTrue(ex2.getMessage().contains("Longitude must be between -180.0 and 180.0"));
    }
}
