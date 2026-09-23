package com.smartcab.routing.distance;

import org.springframework.stereotype.Component;

/**
 * Calculates great-circle distances between two geographic coordinates on Earth
 * using the Haversine spherical formula.
 *
 * <h3>Why Haversine is Used:</h3>
 * <p>
 * The Haversine formula provides a fast, closed-form, and numerically stable trigonometric calculation
 * for great-circle distances over a spherical Earth. It requires no external network calls, mapping APIs,
 * or rate-limited geographic service integrations, making it ideal for high-throughput spatial clustering
 * and route candidate filtering.
 * </p>
 *
 * <h3>Approximation vs Actual Road Distance:</h3>
 * <p>
 * Haversine measures the direct geodesic ("as-the-crow-flies") distance across a sphere with an average
 * Earth radius of ~6371.0 km. It does not account for real-world road network topology, one-way streets,
 * traffic conditions, geographical barriers (rivers, railways), or speed limits. As such, it acts as an
 * admissible lower bound heuristic for actual driving distance and travel time.
 * </p>
 *
 * <h3>Complexity Analysis:</h3>
 * <ul>
 *   <li><b>Time Complexity:</b> O(1) — Requires a constant number of trigonometric (sin, cos, atan2) and arithmetic operations.</li>
 *   <li><b>Space Complexity:</b> O(1) — Operates entirely with primitive stack variables requiring no auxiliary heap allocation.</li>
 * </ul>
 */
@Component
public class HaversineDistanceCalculator implements DistanceCalculator {

    /**
     * Mean Earth radius in kilometers (IUGG recommended value).
     */
    public static final double EARTH_RADIUS_KM = 6371.0;

    @Override
    public double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        validateCoordinates(lat1, lon1);
        validateCoordinates(lat2, lon2);

        if (Double.compare(lat1, lat2) == 0 && Double.compare(lon1, lon2) == 0) {
            return 0.0;
        }

        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);

        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double a = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);

        a = Math.min(1.0, Math.max(0.0, a));

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return EARTH_RADIUS_KM * c;
    }

    private void validateCoordinates(double latitude, double longitude) {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90.0 and 90.0 degrees. Found: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180.0 and 180.0 degrees. Found: " + longitude);
        }
    }
}
