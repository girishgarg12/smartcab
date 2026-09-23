package com.smartcab.service;

import com.smartcab.dto.BookingRequest;
import com.smartcab.dto.BookingResponse;
import com.smartcab.entity.Booking;
import com.smartcab.entity.BookingStatus;
import com.smartcab.entity.Office;
import com.smartcab.entity.Role;
import com.smartcab.entity.User;
import com.smartcab.exception.DuplicateResourceException;
import com.smartcab.exception.ResourceNotFoundException;
import com.smartcab.repository.BookingRepository;
import com.smartcab.repository.OfficeRepository;
import com.smartcab.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final OfficeRepository officeRepository;
    private final RouteReplanningService routeReplanningService;

    @Transactional
    public BookingResponse createBooking(BookingRequest request, String currentUserEmail, String headerIdempotencyKey) {
        User user = getUserByEmail(currentUserEmail);

        if (user.getRole() != Role.EMPLOYEE) {
            throw new AccessDeniedException("Only employees can create bookings");
        }

        // Determine effective idempotency key (header takes precedence, or request body)
        String idempotencyKey = StringUtils.hasText(headerIdempotencyKey)
                ? headerIdempotencyKey.trim()
                : (StringUtils.hasText(request.getIdempotencyKey()) ? request.getIdempotencyKey().trim() : null);

        // Idempotency check: if key already exists, return previous response for same user
        if (StringUtils.hasText(idempotencyKey)) {
            Optional<Booking> existingByKey = bookingRepository.findByIdempotencyKey(idempotencyKey);
            if (existingByKey.isPresent()) {
                Booking existing = existingByKey.get();
                if (existing.getUser().getId().equals(user.getId())) {
                    return mapToResponse(existing);
                } else {
                    throw new DuplicateResourceException("Idempotency key already used by another user");
                }
            }
        }

        Office office = officeRepository.findById(request.getOfficeId())
                .orElseThrow(() -> new ResourceNotFoundException("Office not found with id: " + request.getOfficeId()));

        // Check for existing active booking for same employee, office, and shift
        boolean activeBookingExists = bookingRepository.existsByUserIdAndOfficeIdAndShiftStartTimeAndStatusNot(
                user.getId(),
                office.getId(),
                request.getShiftStartTime(),
                BookingStatus.CANCELLED
        );

        if (activeBookingExists) {
            throw new DuplicateResourceException("Active booking already exists for this employee, office, and shift start time");
        }

        // Snapshot employee's location
        Double pickupLat = request.getPickupLatitude() != null ? request.getPickupLatitude() : user.getLatitude();
        Double pickupLon = request.getPickupLongitude() != null ? request.getPickupLongitude() : user.getLongitude();

        if (pickupLat == null || pickupLon == null) {
            throw new IllegalArgumentException("Employee pickup latitude and longitude must be provided or configured in profile");
        }

        Booking booking = Booking.builder()
                .user(user)
                .office(office)
                .shiftStartTime(request.getShiftStartTime())
                .pickupLatitude(pickupLat)
                .pickupLongitude(pickupLon)
                .status(BookingStatus.BOOKED)
                .idempotencyKey(idempotencyKey)
                .build();

        Booking saved = bookingRepository.save(booking);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings(String currentUserEmail) {
        User user = getUserByEmail(currentUserEmail);
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingById(Long id, String currentUserEmail) {
        User user = getUserByEmail(currentUserEmail);
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + id));

        // Employees can only view their own bookings; Admins can view any booking
        if (user.getRole() != Role.ADMIN && !booking.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("You are not authorized to view this booking");
        }

        return mapToResponse(booking);
    }

    @Transactional
    public BookingResponse cancelBooking(Long id, String currentUserEmail) {
        User user = getUserByEmail(currentUserEmail);
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + id));

        // Employees can only cancel their own bookings; Admins can cancel any booking
        if (user.getRole() != Role.ADMIN && !booking.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("You are not authorized to cancel this booking");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        Booking updated = bookingRepository.save(booking);

        routeReplanningService.replanRouteOnCancellation(updated);

        return mapToResponse(updated);
    }

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
    }

    public BookingResponse mapToResponse(Booking booking) {
        return BookingResponse.builder()
                .id(booking.getId())
                .userId(booking.getUser().getId())
                .userName(booking.getUser().getName())
                .userEmail(booking.getUser().getEmail())
                .officeId(booking.getOffice().getId())
                .officeName(booking.getOffice().getName())
                .shiftStartTime(booking.getShiftStartTime())
                .pickupLatitude(booking.getPickupLatitude())
                .pickupLongitude(booking.getPickupLongitude())
                .status(booking.getStatus())
                .idempotencyKey(booking.getIdempotencyKey())
                .createdAt(booking.getCreatedAt())
                .build();
    }
}
