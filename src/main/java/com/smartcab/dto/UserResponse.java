package com.smartcab.dto;

import com.smartcab.entity.Gender;
import com.smartcab.entity.Role;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private Long id;
    private String name;
    private String email;
    private Role role;
    private Gender gender;
    private Double latitude;
    private Double longitude;
    private boolean active;
    private LocalDateTime createdAt;
}
