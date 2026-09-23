package com.smartcab.routing.clustering;

import com.smartcab.entity.Booking;
import com.smartcab.routing.distance.DistanceCalculator;
import com.smartcab.routing.distance.HaversineDistanceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Greedy, grid-accelerated implementation of {@link EmployeeClusterer}.
 *
 * <h3>Heuristic Strategy (Not Globally Optimal):</h3>
 * <p>
 * Cab pooling is an instance of the NP-hard Capacitated Vehicle Routing Problem (CVRP).
 * This component implements a greedy spatial clustering heuristic rather than an exact or
 * globally optimal algorithm (such as Integer Linear Programming or branch-and-cut).
 * While it does not guarantee the minimum possible number of cabs or absolute minimum total mileage,
 * it guarantees strict compliance with hard cab capacity and detour distance limits with predictable,
 * low computational latency.
 * </p>
 *
 * <h3>Algorithmic Workflow:</h3>
 * <ol>
 *   <li><b>Deterministic Ordering:</b> Sorts input bookings by ID (or coordinates) to ensure deterministic,
 *       reproducible clustering behavior across executions.</li>
 *   <li><b>Spatial Indexing:</b> Populates a {@link GridSpatialIndex} with cell sizes scaled to {@code maxDetourKm},
 *       preventing \(\mathcal{O}(N^2)\) all-pairs comparisons.</li>
 *   <li><b>Seed Selection:</b> Selects the next unassigned booking as the cluster seed.</li>
 *   <li><b>Candidate Pruning:</b> Queries the spatial grid index for passengers in the 9 adjacent cells (\(3 \times 3\) window).</li>
 *   <li><b>Greedy Expansion:</b> Computes Haversine distances to the seed, discards candidates exceeding {@code maxDetourKm},
 *       sorts eligible candidates in ascending distance order, and adds them to the cluster until {@code cabCapacity} is reached.</li>
 *   <li><b>Iteration:</b> Marks assigned passengers and repeats until all bookings are clustered.</li>
 * </ol>
 *
 * <h3>Complexity Analysis:</h3>
 * <ul>
 *   <li><b>Time Complexity:</b>
 *     <ul>
 *       <li>Initial sorting: \(\mathcal{O}(N \log N)\) where \(N\) is the number of bookings.</li>
 *       <li>Grid population: \(\mathcal{O}(N)\) via \(\mathcal{O}(1)\) hash insertions.</li>
 *       <li>Cluster formation: For each of the \(\mathcal{O}(N)\) seed bookings, candidate lookup in the grid takes \(\mathcal{O}(K)\)
 *           where \(K\) is the local candidate density in adjacent cells, and sorting those candidates takes \(\mathcal{O}(K \log K)\).</li>
 *       <li><b>Total Average Time:</b> \(\mathcal{O}(N \log N + N \cdot K \log K)\). In typical urban spatial distributions where
 *           \(K \ll N\) (bounded constant density per cell), this simplifies to \(\mathcal{O}(N \log N)\). In the pathological
 *           worst case (all \(N\) passengers at the identical coordinates), \(K = N\), yielding \(\mathcal{O}(N^2 \log N)\).</li>
 *     </ul>
 *   </li>
 *   <li><b>Space Complexity:</b> \(\mathcal{O}(N)\) auxiliary space to maintain the spatial grid index, assigned tracking sets,
 *       and result cluster lists.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class GridBasedEmployeeClusterer implements EmployeeClusterer {

    private final DistanceCalculator distanceCalculator;

    /**
     * Default constructor utilizing {@link HaversineDistanceCalculator}.
     */
    public GridBasedEmployeeClusterer() {
        this(new HaversineDistanceCalculator());
    }

    @Override
    public List<List<Booking>> clusterBookings(List<Booking> bookings, int cabCapacity, double maxDetourKm) {
        if (bookings == null || bookings.isEmpty()) {
            return Collections.emptyList();
        }
        if (cabCapacity <= 0) {
            throw new IllegalArgumentException("Cab capacity must be greater than 0. Found: " + cabCapacity);
        }
        if (maxDetourKm < 0.0) {
            throw new IllegalArgumentException("Maximum detour distance must be non-negative. Found: " + maxDetourKm);
        }

        // 1. Sort bookings deterministically by ID or coordinates
        List<Booking> sortedBookings = new ArrayList<>(bookings);
        sortedBookings.sort(Comparator.comparing(
                (Booking b) -> b.getId() != null ? b.getId() : 0L
        ).thenComparingDouble(
                b -> b.getPickupLatitude() != null ? b.getPickupLatitude() : 0.0
        ).thenComparingDouble(
                b -> b.getPickupLongitude() != null ? b.getPickupLongitude() : 0.0
        ));

        // 2. Build spatial grid index scaled to maxDetourKm
        double cellSizeKm = Math.max(1.0, maxDetourKm);
        GridSpatialIndex spatialIndex = GridSpatialIndex.fromCellSizeKm(cellSizeKm);
        for (Booking booking : sortedBookings) {
            spatialIndex.add(booking);
        }

        Set<Long> assignedIds = new HashSet<>();
        Set<Booking> assignedInstances = new HashSet<>();
        List<List<Booking>> clusters = new ArrayList<>();

        // 3. Greedy cluster formation
        for (Booking seed : sortedBookings) {
            if (isAssigned(seed, assignedIds, assignedInstances)) {
                continue;
            }

            List<Booking> currentCluster = new ArrayList<>();
            currentCluster.add(seed);
            markAssigned(seed, assignedIds, assignedInstances);

            if (currentCluster.size() == cabCapacity) {
                clusters.add(currentCluster);
                continue;
            }

            // Retrieve nearby spatial candidates from the 3x3 cell neighborhood
            List<Booking> nearbyCandidates = spatialIndex.findNearby(
                    seed.getPickupLatitude(),
                    seed.getPickupLongitude()
            );

            // Filter unassigned candidates and calculate Haversine distance
            List<CandidateDistance> eligibleCandidates = new ArrayList<>();
            for (Booking candidate : nearbyCandidates) {
                if (isAssigned(candidate, assignedIds, assignedInstances)) {
                    continue;
                }

                double distance = distanceCalculator.calculateDistanceKm(
                        seed.getPickupLatitude(),
                        seed.getPickupLongitude(),
                        candidate.getPickupLatitude(),
                        candidate.getPickupLongitude()
                );

                if (distance <= maxDetourKm) {
                    eligibleCandidates.add(new CandidateDistance(candidate, distance));
                }
            }

            // Sort candidates in increasing distance order from seed
            eligibleCandidates.sort(Comparator.comparingDouble(CandidateDistance::distance)
                    .thenComparing(c -> c.booking().getId() != null ? c.booking().getId() : 0L));

            // Greedily fill cab up to capacity
            for (CandidateDistance candidate : eligibleCandidates) {
                if (currentCluster.size() >= cabCapacity) {
                    break;
                }
                if (!isAssigned(candidate.booking(), assignedIds, assignedInstances)) {
                    currentCluster.add(candidate.booking());
                    markAssigned(candidate.booking(), assignedIds, assignedInstances);
                }
            }

            clusters.add(currentCluster);
        }

        return clusters;
    }

    private boolean isAssigned(Booking booking, Set<Long> assignedIds, Set<Booking> assignedInstances) {
        if (booking.getId() != null) {
            return assignedIds.contains(booking.getId());
        }
        return assignedInstances.contains(booking);
    }

    private void markAssigned(Booking booking, Set<Long> assignedIds, Set<Booking> assignedInstances) {
        if (booking.getId() != null) {
            assignedIds.add(booking.getId());
        } else {
            assignedInstances.add(booking);
        }
    }

    private record CandidateDistance(Booking booking, double distance) {
    }
}
