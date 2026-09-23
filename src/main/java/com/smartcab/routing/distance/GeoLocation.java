package com.smartcab.routing.distance;

/**
 * Immutable representation of a geographic coordinate pair (latitude, longitude).
 *
 * @param latitude  Latitude in degrees [-90.0, 90.0]
 * @param longitude Longitude in degrees [-180.0, 180.0]
 */
public record GeoLocation(double latitude, double longitude) {
}
