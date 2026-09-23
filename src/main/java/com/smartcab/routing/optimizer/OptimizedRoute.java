package com.smartcab.routing.optimizer;

import com.smartcab.entity.Office;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents the complete optimized route plan for a single cab group.
 *
 * @param stops           Ordered list of pickup stops (1-indexed sequence)
 * @param totalDistanceKm Total driving distance across all legs terminating at the office
 * @param officeEta       Estimated arrival time at the destination office
 * @param office          Destination office
 */
public record OptimizedRoute(
        List<OptimizedStop> stops,
        double totalDistanceKm,
        LocalDateTime officeEta,
        Office office
) {
}
