package com.smartcab.controller;

import com.smartcab.dto.BookingRequest;
import com.smartcab.dto.BookingResponse;
import com.smartcab.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody BookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            Principal principal) {
        BookingResponse response = bookingService.createBooking(request, principal.getName(), headerIdempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'ADMIN')")
    public ResponseEntity<List<BookingResponse>> getMyBookings(Principal principal) {
        return ResponseEntity.ok(bookingService.getMyBookings(principal.getName()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'ADMIN')")
    public ResponseEntity<BookingResponse> getBookingById(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(bookingService.getBookingById(id, principal.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'ADMIN')")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(bookingService.cancelBooking(id, principal.getName()));
    }
}
