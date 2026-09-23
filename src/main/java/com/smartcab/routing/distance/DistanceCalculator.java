package com.smartcab.routing.distance;

/**
 * Interface defining contract for spatial distance calculations between geographic coordinates.
 */
public interface DistanceCalculator {

    /**
     * Calculates the distance in kilometers between two geographic coordinate points.
     *
     * @param lat1 Latitude of source point [-90.0, 90.0]
     * @param lon1 Longitude of source point [-180.0, 180.0]
     * @param lat2 Latitude of destination point [-90.0, 90.0]
     * @param lon2 Longitude of destination point [-180.0, 180.0]
     * @return Distance in kilometers
     * @throws IllegalArgumentException if coordinates are out of valid range
     */
    double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2);

    /**
     * Convenience method to calculate distance between two {@link GeoLocation} objects.
     *
     * @param from Source location
     * @param to   Destination location
     * @return Distance in kilometers
     */
    default double calculateDistanceKm(GeoLocation from, GeoLocation to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Locations must not be null");
        }
        return calculateDistanceKm(from.latitude(), from.longitude(), to.latitude(), to.longitude());
    }
}
