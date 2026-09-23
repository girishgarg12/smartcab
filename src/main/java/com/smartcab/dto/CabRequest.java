package com.smartcab.dto;

import com.smartcab.entity.CabStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CabRequest {

    @NotBlank(message = "Vehicle number is required")
    private String vehicleNumber;

    @NotNull(message = "Capacity is required")
    private Integer capacity;

    private CabStatus status;

    @AssertTrue(message = "Cab capacity must be either 4 or 6")
    public boolean isValidCapacity() {
        return capacity == null || capacity == 4 || capacity == 6;
    }
}
