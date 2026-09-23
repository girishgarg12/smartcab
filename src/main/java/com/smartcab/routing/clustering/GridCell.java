package com.smartcab.routing.clustering;

/**
 * Immutable value object representing a discrete 2D spatial grid cell bucket.
 *
 * @param x Column index along longitude
 * @param y Row index along latitude
 */
public record GridCell(int x, int y) {

    /**
     * Returns an adjacent grid cell offset by the given delta coordinates.
     *
     * @param dx Horizontal offset in columns
     * @param dy Vertical offset in rows
     * @return New GridCell with applied offset
     */
    public GridCell offset(int dx, int dy) {
        return new GridCell(this.x + dx, this.y + dy);
    }
}
