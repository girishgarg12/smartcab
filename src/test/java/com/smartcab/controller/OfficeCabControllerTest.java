package com.smartcab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcab.dto.CabRequest;
import com.smartcab.dto.OfficeRequest;
import com.smartcab.entity.CabStatus;
import com.smartcab.entity.Role;
import com.smartcab.entity.User;
import com.smartcab.repository.CabRepository;
import com.smartcab.repository.OfficeRepository;
import com.smartcab.repository.UserRepository;
import com.smartcab.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class OfficeCabControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OfficeRepository officeRepository;

    @Autowired
    private CabRepository cabRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        cabRepository.deleteAll();
        officeRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .name("Admin User")
                .email("admin@smartcab.com")
                .passwordHash(passwordEncoder.encode("adminPass123"))
                .role(Role.ADMIN)
                .active(true)
                .build());
        adminToken = jwtUtils.generateToken(admin);

        User employee = userRepository.save(User.builder()
                .name("Employee User")
                .email("employee@smartcab.com")
                .passwordHash(passwordEncoder.encode("empPass123"))
                .role(Role.EMPLOYEE)
                .active(true)
                .build());
        employeeToken = jwtUtils.generateToken(employee);
    }

    @Test
    void testCreateAndGetValidOffice() throws Exception {
        OfficeRequest request = OfficeRequest.builder()
                .name("Tech Park Campus")
                .address("100 Outer Ring Road, Bangalore")
                .latitude(12.9279)
                .longitude(77.6271)
                .build();

        String responseJson = mockMvc.perform(post("/api/offices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("Tech Park Campus")))
                .andExpect(jsonPath("$.latitude", is(12.9279)))
                .andExpect(jsonPath("$.longitude", is(77.6271)))
                .andReturn().getResponse().getContentAsString();

        Number id = objectMapper.readTree(responseJson).get("id").numberValue();

        // Employee can read the created office
        mockMvc.perform(get("/api/offices/" + id)
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.intValue())))
                .andExpect(jsonPath("$.name", is("Tech Park Campus")));

        // Employee can list all offices
        mockMvc.perform(get("/api/offices")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void testOfficeInvalidCoordinates() throws Exception {
        // Latitude > 90
        OfficeRequest invalidLat = OfficeRequest.builder()
                .name("Invalid Office")
                .latitude(95.0)
                .longitude(77.0)
                .build();

        mockMvc.perform(post("/api/offices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidLat)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("Latitude must be between -90 and 90"))));

        // Longitude > 180
        OfficeRequest invalidLon = OfficeRequest.builder()
                .name("Invalid Office")
                .latitude(12.0)
                .longitude(195.0)
                .build();

        mockMvc.perform(post("/api/offices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidLon)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("Longitude must be between -180 and 180"))));
    }

    @Test
    void testCreateValidCab() throws Exception {
        // Capacity 4
        CabRequest cab4 = CabRequest.builder()
                .vehicleNumber("KA01AB1234")
                .capacity(4)
                .status(CabStatus.AVAILABLE)
                .build();

        mockMvc.perform(post("/api/cabs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cab4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.vehicleNumber", is("KA01AB1234")))
                .andExpect(jsonPath("$.capacity", is(4)))
                .andExpect(jsonPath("$.status", is("AVAILABLE")));

        // Capacity 6
        CabRequest cab6 = CabRequest.builder()
                .vehicleNumber("KA01CD5678")
                .capacity(6)
                .build();

        mockMvc.perform(post("/api/cabs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cab6)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.vehicleNumber", is("KA01CD5678")))
                .andExpect(jsonPath("$.capacity", is(6)))
                .andExpect(jsonPath("$.status", is("AVAILABLE")));
    }

    @Test
    void testCabInvalidCapacity() throws Exception {
        CabRequest invalidCapacity = CabRequest.builder()
                .vehicleNumber("KA01XX9999")
                .capacity(5)
                .build();

        mockMvc.perform(post("/api/cabs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCapacity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("Cab capacity must be either 4 or 6"))));
    }

    @Test
    void testCabDuplicateVehicleNumber() throws Exception {
        CabRequest cab = CabRequest.builder()
                .vehicleNumber("KA05ZZ1111")
                .capacity(4)
                .build();

        mockMvc.perform(post("/api/cabs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cab)))
                .andExpect(status().isCreated());

        // Duplicate vehicle number attempt
        mockMvc.perform(post("/api/cabs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cab)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.message", containsString("Vehicle number already exists")));
    }

    @Test
    void testUnauthorizedEmployeeAccess() throws Exception {
        // Employee attempting to create Office -> 403 Forbidden
        OfficeRequest officeRequest = OfficeRequest.builder()
                .name("Unauthorized Office")
                .latitude(12.0)
                .longitude(77.0)
                .build();

        mockMvc.perform(post("/api/offices")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(officeRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")));

        // Employee attempting to create Cab -> 403 Forbidden
        CabRequest cabRequest = CabRequest.builder()
                .vehicleNumber("KA02EE3333")
                .capacity(4)
                .build();

        mockMvc.perform(post("/api/cabs")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cabRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")));
    }
}
