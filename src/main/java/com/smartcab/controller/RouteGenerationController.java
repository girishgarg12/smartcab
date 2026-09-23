package com.smartcab.controller;

import com.smartcab.dto.RouteGenerationRequest;
import com.smartcab.dto.RouteResponse;
import com.smartcab.service.RouteGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller handling employee cab route generation workflows.
 * Restricted to administrators.
 */
@RestController
@RequestMapping("/api/routing")
@RequiredArgsConstructor
public class RouteGenerationController {

    private final RouteGenerationService routeGenerationService;

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
}
