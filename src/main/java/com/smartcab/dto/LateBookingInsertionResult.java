package com.smartcab.dto;

import lombok.*;

/**
 * Result model representing the outcome of attempting to insert a late booking into an existing route.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LateBookingInsertionResult {

    private boolean inserted;
    private Long bookingId;
    private Long routeId;
    private Long cabId;
    private String vehicleNumber;
    private RouteResponse updatedRoute;
    private String failureReason;

    public static LateBookingInsertionResult success(
            Long bookingId,
            Long routeId,
            Long cabId,
            String vehicleNumber,
            RouteResponse updatedRoute) {
        return LateBookingInsertionResult.builder()
                .inserted(true)
                .bookingId(bookingId)
                .routeId(routeId)
                .cabId(cabId)
                .vehicleNumber(vehicleNumber)
                .updatedRoute(updatedRoute)
                .build();
    }

    public static LateBookingInsertionResult rejected(Long bookingId, String failureReason) {
        return LateBookingInsertionResult.builder()
                .inserted(false)
                .bookingId(bookingId)
                .failureReason(failureReason)
                .build();
    }
}
