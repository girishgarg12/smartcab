package com.smartcab.repository;

import com.smartcab.entity.Booking;
import com.smartcab.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);
    List<Booking> findByStatus(BookingStatus status);
}
