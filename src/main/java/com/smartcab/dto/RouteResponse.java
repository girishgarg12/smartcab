package com.smartcab.dto;

import com.smartcab.entity.RouteStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteResponse {

    private Long id;
    private CabResponse cab;
    private OfficeResponse office;
    private LocalDateTime shiftStartTime;
    private RouteStatus status;
    private Double totalDistanceKm;
    private LocalDateTime estimatedArrivalTime;
    private boolean requiresEscortGuard;
    private List<RouteStopResponse> stops;
}
