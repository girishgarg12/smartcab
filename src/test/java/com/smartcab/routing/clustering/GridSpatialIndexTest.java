package com.smartcab.routing.clustering;

import com.smartcab.entity.Booking;
import com.smartcab.entity.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridSpatialIndexTest {

    private GridSpatialIndex index;

    @BeforeEach
    void setUp() {
        // Use ~1.0 km cells (~0.009 degrees)
        index = GridSpatialIndex.fromCellSizeKm(1.0);
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
    void testAddAndFindInCell() {
        Booking b1 = createBooking(1L, 12.9750, 77.5950);
        Booking b2 = createBooking(2L, 12.9751, 77.5951); // safely inside the same cell

        index.add(b1);
        index.add(b2);

        assertEquals(2, index.size());

        GridCell cell = index.getCell(12.9750, 77.5950);
        List<Booking> inCell = index.findInCell(cell);

        assertEquals(2, inCell.size());
        assertTrue(inCell.contains(b1));
        assertTrue(inCell.contains(b2));
    }

    @Test
    void testFindInNeighboringCells() {
        double baseLat = 12.9700;
        double baseLon = 77.5900;
        double step = index.getCellSizeDegrees();

        // Center booking
        Booking center = createBooking(1L, baseLat, baseLon);
        GridCell centerCell = index.getCell(baseLat, baseLon);

        // Immediate neighbors in adjacent cells
        Booking neighborEast = createBooking(2L, baseLat, baseLon + step * 1.1);
        Booking neighborNorth = createBooking(3L, baseLat + step * 1.1, baseLon);
        Booking neighborNorthEast = createBooking(4L, baseLat + step * 1.1, baseLon + step * 1.1);

        // Distant booking far outside the 3x3 window (10 cells away)
        Booking distant = createBooking(5L, baseLat + step * 10, baseLon + step * 10);

        index.add(center);
        index.add(neighborEast);
        index.add(neighborNorth);
        index.add(neighborNorthEast);
        index.add(distant);

        assertEquals(5, index.size());

        // Neighbors only (excluding center)
        List<Booking> neighborsOnly = index.findInNeighboringCells(centerCell, false);
        assertEquals(3, neighborsOnly.size());
        assertTrue(neighborsOnly.contains(neighborEast));
        assertTrue(neighborsOnly.contains(neighborNorth));
        assertTrue(neighborsOnly.contains(neighborNorthEast));
        assertFalse(neighborsOnly.contains(center));
        assertFalse(neighborsOnly.contains(distant));

        // Neighbors including center (3x3 kernel)
        List<Booking> nearbyWithCenter = index.findInNeighboringCells(centerCell, true);
        assertEquals(4, nearbyWithCenter.size());
        assertTrue(nearbyWithCenter.contains(center));
        assertTrue(nearbyWithCenter.contains(neighborEast));
        assertTrue(nearbyWithCenter.contains(neighborNorth));
        assertTrue(nearbyWithCenter.contains(neighborNorthEast));
        assertFalse(nearbyWithCenter.contains(distant));
    }

    @Test
    void testRemoveBooking() {
        Booking b1 = createBooking(1L, 12.9750, 77.5950);
        Booking b2 = createBooking(2L, 12.9751, 77.5951);

        index.add(b1);
        index.add(b2);
        assertEquals(2, index.size());

        // Remove b1
        boolean removed = index.remove(b1);
        assertTrue(removed);
        assertEquals(1, index.size());

        GridCell cell = index.getCell(12.9750, 77.5950);
        List<Booking> remaining = index.findInCell(cell);
        assertEquals(1, remaining.size());
        assertEquals(2L, remaining.get(0).getId());

        // Attempt to remove non-existent booking
        Booking nonExistent = createBooking(99L, 12.9750, 77.5950);
        assertFalse(index.remove(nonExistent));
        assertEquals(1, index.size());

        // Remove remaining
        assertTrue(index.remove(b2));
        assertEquals(0, index.size());
        assertTrue(index.findInCell(cell).isEmpty());
    }

    @Test
    void testClearIndex() {
        index.add(createBooking(1L, 12.9716, 77.5946));
        index.add(createBooking(2L, 13.0000, 77.6000));
        assertEquals(2, index.size());

        index.clear();
        assertEquals(0, index.size());
        assertTrue(index.findNearby(12.9716, 77.5946).isEmpty());
    }

    @Test
    void testConfigurableCellSize() {
        double lat1 = 12.9700;
        double lon1 = 77.5900;
        double lat2 = 12.9750;
        double lon2 = 77.5950;

        // With small cell size (0.2 km), points are in different cells
        GridSpatialIndex fineIndex = GridSpatialIndex.fromCellSizeKm(0.2);
        assertNotEquals(fineIndex.getCell(lat1, lon1), fineIndex.getCell(lat2, lon2));

        // With large cell size (10 km), points fall into the same cell
        GridSpatialIndex coarseIndex = GridSpatialIndex.fromCellSizeKm(10.0);
        assertEquals(coarseIndex.getCell(lat1, lon1), coarseIndex.getCell(lat2, lon2));
    }

    @Test
    void testInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> new GridSpatialIndex(0.0));
        assertThrows(IllegalArgumentException.class, () -> new GridSpatialIndex(-1.0));
        assertThrows(IllegalArgumentException.class, () -> GridSpatialIndex.fromCellSizeKm(-5.0));

        assertThrows(IllegalArgumentException.class, () -> index.add(null));
        assertThrows(IllegalArgumentException.class, () ->
                index.add(Booking.builder().pickupLatitude(null).pickupLongitude(77.0).build()));
        assertThrows(IllegalArgumentException.class, () ->
                index.add(Booking.builder().pickupLatitude(12.0).pickupLongitude(null).build()));
    }
}
