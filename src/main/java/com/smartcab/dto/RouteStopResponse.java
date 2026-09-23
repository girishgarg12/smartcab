package com.smartcab.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteStopResponse {

    private Long id;
    private Long bookingId;
    private Long employeeId;
    private String employeeName;
    private Integer sequence;
    private LocalDateTime pickupEta;
    private Integer rideDurationMinutes;
    private Double pickupLatitude;
    private Double pickupLongitude;
}
