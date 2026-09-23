package com.smartcab.routing.clustering;

import com.smartcab.entity.Booking;

import java.util.*;

/**
 * A simple uniform grid-based 2D spatial index for employee booking locations.
 *
 * <h3>Why Grid Indexing is Used:</h3>
 * <p>
 * Spatial grid indexing (bucket hashing) partitions 2D geographic coordinates into uniform discrete cells.
 * It provides a lightweight, pure-Java mechanism for spatial proximity candidate retrieval without
 * requiring complex multi-dimensional tree structures (R-Trees, Quadtrees, KD-Trees) or external GIS database
 * extensions (PostGIS). Coordinates are mapped to cell buckets in \(\mathcal{O}(1)\) time using floor division.
 * </p>
 *
 * <h3>Average-Case Candidate Lookup Idea:</h3>
 * <p>
 * To find pooling candidates for a booking at \((lat, lon)\), the index computes its cell \((x, y)\)
 * and retrieves bookings only from that cell and its immediate 8 adjacent neighbors (\(3 \times 3\) window).
 * This eliminates the vast majority of non-viable, distant bookings upfront, reducing subsequent
 * expensive distance calculations (Haversine formula) and routing permutations from \(\mathcal{O}(N^2)\)
 * to \(\mathcal{O}(N \cdot K)\), where \(K\) is the small average number of neighbors in the spatial window.
 * </p>
 *
 * <h3>Complexity Analysis:</h3>
 * <ul>
 *   <li><b>Insertion (add):</b> \(\mathcal{O}(1)\) average time — computes cell via floor division and appends to bucket list.</li>
 *   <li><b>Direct Lookup (findInCell):</b> \(\mathcal{O}(1)\) average time — hash map key lookup.</li>
 *   <li><b>Neighbor Lookup (findInNeighboringCells):</b> \(\mathcal{O}(K)\) average time, where \(K\) is the total number of
 *       bookings in the 9 neighboring cells.</li>
 *   <li><b>Deletion (remove):</b> \(\mathcal{O}(B)\) average time, where \(B\) is the number of bookings within that single cell bucket.</li>
 *   <li><b>Space Complexity:</b> \(\mathcal{O}(N)\) auxiliary memory, where \(N\) is the total number of indexed bookings.</li>
 * </ul>
 *
 * <h3>Limitations for Uneven Geographic Distributions:</h3>
 * <ul>
 *   <li><b>Density Skew / Clustering Collapse:</b> When bookings are highly concentrated (e.g. dense urban residential hubs
 *       or tech parks), hundreds of bookings can fall into the same single cell. In this scenario, candidate lookup
 *       in that cell degrades towards linear scan \(\mathcal{O}(N)\).</li>
 *   <li><b>Sparsity Overhead:</b> In rural or sparsely populated areas, many cells remain empty, yielding zero candidates
 *       despite scanning all 9 cells.</li>
 *   <li><b>Fixed Spatial Granularity:</b> Unlike adaptive data structures (such as Quadtrees) which recursively subdivide
 *       dense areas and leave sparse areas coarse, a uniform grid enforces a static cell size across all regions regardless of density.</li>
 *   <li><b>Boundary Artifacts:</b> Points situated close to each other on opposite sides of a cell boundary fall into different
 *       cells; this is mitigated by always inspecting the 8 adjacent neighboring cells (3x3 kernel).</li>
 * </ul>
 */
public class GridSpatialIndex {

    /**
     * Approximate kilometers per degree of latitude on Earth.
     */
    public static final double KM_PER_DEGREE = 111.32;

    /**
     * Default cell size in degrees (~0.018 degrees corresponds to ~2.0 km).
     */
    public static final double DEFAULT_CELL_SIZE_DEGREES = 0.018;

    private final double cellSizeDegrees;
    private final Map<GridCell, List<Booking>> grid;
    private int totalCount;

    /**
     * Constructs a GridSpatialIndex with the default cell size (~2.0 km).
     */
    public GridSpatialIndex() {
        this(DEFAULT_CELL_SIZE_DEGREES);
    }

    /**
     * Constructs a GridSpatialIndex with a custom cell size in degrees.
     *
     * @param cellSizeDegrees Angular span of each grid cell bucket in degrees (> 0)
     */
    public GridSpatialIndex(double cellSizeDegrees) {
        if (cellSizeDegrees <= 0.0) {
            throw new IllegalArgumentException("Cell size in degrees must be positive. Found: " + cellSizeDegrees);
        }
        this.cellSizeDegrees = cellSizeDegrees;
        this.grid = new HashMap<>();
        this.totalCount = 0;
    }

    /**
     * Factory method creating a GridSpatialIndex configured with an approximate cell size in kilometers.
     *
     * @param cellSizeKm Desired cell size in kilometers (> 0)
     * @return Initialized GridSpatialIndex
     */
    public static GridSpatialIndex fromCellSizeKm(double cellSizeKm) {
        if (cellSizeKm <= 0.0) {
            throw new IllegalArgumentException("Cell size in km must be positive. Found: " + cellSizeKm);
        }
        double degrees = cellSizeKm / KM_PER_DEGREE;
        return new GridSpatialIndex(degrees);
    }

    /**
     * Computes the discrete {@link GridCell} coordinates for the given latitude and longitude.
     *
     * @param latitude  Latitude in degrees [-90.0, 90.0]
     * @param longitude Longitude in degrees [-180.0, 180.0]
     * @return Corresponding GridCell
     */
    public GridCell getCell(double latitude, double longitude) {
        int x = (int) Math.floor(longitude / cellSizeDegrees);
        int y = (int) Math.floor(latitude / cellSizeDegrees);
        return new GridCell(x, y);
    }

    /**
     * Adds a booking to the spatial index.
     *
     * @param booking Booking to index (must have non-null pickup coordinates)
     */
    public void add(Booking booking) {
        if (booking == null) {
            throw new IllegalArgumentException("Booking must not be null");
        }
        if (booking.getPickupLatitude() == null || booking.getPickupLongitude() == null) {
            throw new IllegalArgumentException("Booking pickup coordinates must not be null");
        }

        GridCell cell = getCell(booking.getPickupLatitude(), booking.getPickupLongitude());
        grid.computeIfAbsent(cell, k -> new ArrayList<>()).add(booking);
        totalCount++;
    }

    /**
     * Retrieves all bookings located inside the specified grid cell.
     *
     * @param cell Target grid cell
     * @return List of bookings in this cell (unmodifiable or new copy)
     */
    public List<Booking> findInCell(GridCell cell) {
        if (cell == null) {
            return Collections.emptyList();
        }
        List<Booking> list = grid.get(cell);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    /**
     * Retrieves bookings located in the 8 neighboring cells around the given cell,
     * optionally including the cell itself.
     *
     * @param cell        Center grid cell
     * @param includeSelf If true, bookings in the center cell are also included (total 9 cells)
     * @return Combined list of bookings from neighboring cells
     */
    public List<Booking> findInNeighboringCells(GridCell cell, boolean includeSelf) {
        if (cell == null) {
            return Collections.emptyList();
        }

        List<Booking> results = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (!includeSelf && dx == 0 && dy == 0) {
                    continue;
                }
                GridCell neighbor = cell.offset(dx, dy);
                List<Booking> bucket = grid.get(neighbor);
                if (bucket != null && !bucket.isEmpty()) {
                    results.addAll(bucket);
                }
            }
        }
        return results;
    }

    /**
     * Convenience method to find all bookings in the 3x3 cell neighborhood of a geographic location.
     *
     * @param latitude  Search latitude
     * @param longitude Search longitude
     * @return List of bookings in the 9-cell spatial window
     */
    public List<Booking> findNearby(double latitude, double longitude) {
        GridCell center = getCell(latitude, longitude);
        return findInNeighboringCells(center, true);
    }

    /**
     * Removes a booking from the spatial index.
     *
     * @param booking Booking to remove
     * @return true if the booking was found and removed, false otherwise
     */
    public boolean remove(Booking booking) {
        if (booking == null || booking.getPickupLatitude() == null || booking.getPickupLongitude() == null) {
            return false;
        }

        GridCell cell = getCell(booking.getPickupLatitude(), booking.getPickupLongitude());
        List<Booking> bucket = grid.get(cell);
        if (bucket == null) {
            return false;
        }

        boolean removed;
        if (booking.getId() != null) {
            removed = bucket.removeIf(b -> Objects.equals(b.getId(), booking.getId()));
        } else {
            removed = bucket.remove(booking);
        }

        if (removed) {
            totalCount--;
            if (bucket.isEmpty()) {
                grid.remove(cell);
            }
        }
        return removed;
    }

    /**
     * Returns the total number of bookings currently indexed.
     *
     * @return Indexed booking count
     */
    public int size() {
        return totalCount;
    }

    /**
     * Clears all indexed entries.
     */
    public void clear() {
        grid.clear();
        totalCount = 0;
    }

    /**
     * Returns the configured cell size in degrees.
     *
     * @return Cell size in degrees
     */
    public double getCellSizeDegrees() {
        return cellSizeDegrees;
    }
}
