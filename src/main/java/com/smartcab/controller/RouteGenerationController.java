package com.smartcab.controller;

import com.smartcab.dto.LateBookingInsertionResult;
import com.smartcab.dto.RouteGenerationRequest;
import com.smartcab.dto.RouteResponse;
import com.smartcab.service.LateBookingService;
import com.smartcab.service.RouteGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller handling employee cab route generation and dynamic routing workflows.
 * Restricted to administrators.
 */
@RestController
@RequestMapping("/api/routing")
@RequiredArgsConstructor
public class RouteGenerationController {

    private final RouteGenerationService routeGenerationService;
    private final LateBookingService lateBookingService;

    /**
     * Triggers the automated clustering, cab assignment, and exact route optimization workflow
     * for pending employee bookings.
     *
     * @param request Optional filtering criteria and routing configuration
     * @return List of generated route summaries
     */
    @PostMapping("/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<RouteResponse>> generateRoutes(
            @RequestBody(required = false) RouteGenerationRequest request) {

        List<RouteResponse> routes = routeGenerationService.generateRoutes(request);
        return ResponseEntity.ok(routes);
    }

    /**
     * Attempts to dynamically insert a late booking into an already generated nearby route
     * serving the same office and shift.
     *
     * @param bookingId ID of the late booking to insert
     * @return Result of the insertion attempt with updated route or rejection explanation
     */
    @PostMapping("/late-booking/{bookingId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LateBookingInsertionResult> insertLateBooking(@PathVariable Long bookingId) {
        LateBookingInsertionResult result = lateBookingService.tryInsertLateBooking(bookingId);
        return ResponseEntity.ok(result);
    }
}
