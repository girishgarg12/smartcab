package com.smartcab.dto;

import com.smartcab.entity.BookingStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {

    private Long id;
    private Long userId;
    private String userName;
    private String userEmail;
    private Long officeId;
    private String officeName;
    private LocalDateTime shiftStartTime;
    private Double pickupLatitude;
    private Double pickupLongitude;
    private BookingStatus status;
    private String idempotencyKey;
    private LocalDateTime createdAt;
}
