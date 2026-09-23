package com.smartcab.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteGenerationRequest {

    private Long officeId;
    private LocalDateTime shiftStartTime;
    private Double maxDetourKm;
    private Integer maxRideTimeMinutes;
    private Double averageSpeedKmh;
    private Integer cabCapacity;
    private Boolean forceRegenerate;
}
