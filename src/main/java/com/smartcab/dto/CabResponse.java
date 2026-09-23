package com.smartcab.dto;

import com.smartcab.entity.CabStatus;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CabResponse {

    private Long id;
    private String vehicleNumber;
    private Integer capacity;
    private CabStatus status;
}
