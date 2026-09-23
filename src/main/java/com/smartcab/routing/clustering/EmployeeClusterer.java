package com.smartcab.routing.clustering;

import com.smartcab.entity.Booking;

import java.util.List;

/**
 * Strategy interface for clustering employee bookings into cab groups based on spatial proximity
 * and capacity constraints.
 */
public interface EmployeeClusterer {

    /**
     * Groups active bookings for the same office and shift into cab clusters.
     *
     * @param bookings     List of unassigned active bookings
     * @param cabCapacity  Maximum number of passengers allowed per cab
     * @param maxDetourKm  Maximum allowed geographic distance (in km) between clustered passenger pickups
     * @return List of passenger clusters, where each cluster contains at most {@code cabCapacity} bookings
     */
    List<List<Booking>> clusterBookings(List<Booking> bookings, int cabCapacity, double maxDetourKm);
}
