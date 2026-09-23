package com.smartcab.repository;

import com.smartcab.entity.Booking;
import com.smartcab.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);
    List<Booking> findByStatus(BookingStatus status);
    List<Booking> findByStatusIn(List<BookingStatus> statuses);
    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<Booking> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndOfficeIdAndShiftStartTimeAndStatusNot(
            Long userId,
            Long officeId,
            LocalDateTime shiftStartTime,
            BookingStatus status
    );
}
