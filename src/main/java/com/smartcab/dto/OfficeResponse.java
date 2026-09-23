package com.smartcab.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfficeResponse {

    private Long id;
    private String name;
    private String address;
    private Double latitude;
    private Double longitude;
}
