package com.smartcab.controller;

import com.jayway.jsonpath.JsonPath;
import com.smartcab.entity.BookingStatus;
import com.smartcab.entity.Office;
import com.smartcab.entity.Role;
import com.smartcab.entity.User;
import com.smartcab.repository.BookingRepository;
import com.smartcab.repository.OfficeRepository;
import com.smartcab.repository.RouteRepository;
import com.smartcab.repository.RouteStopRepository;
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

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class BookingControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private OfficeRepository officeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    private MockMvc mockMvc;

    private User emp1;
    private User emp2;
    private User admin;
    private Office office;

    private String emp1Token;
    private String emp2Token;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        routeStopRepository.deleteAll();
        routeRepository.deleteAll();
        bookingRepository.deleteAll();
        officeRepository.deleteAll();
        userRepository.deleteAll();

        office = officeRepository.save(Office.builder()
                .name("EcoWorld Office")
                .address("Outer Ring Road, Bangalore")
                .latitude(12.9234)
                .longitude(77.6852)
                .build());

        emp1 = userRepository.save(User.builder()
                .name("Employee One")
                .email("emp1@smartcab.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.EMPLOYEE)
                .latitude(12.9716)
                .longitude(77.5946)
                .active(true)
                .build());
        emp1Token = jwtUtils.generateToken(emp1);

        emp2 = userRepository.save(User.builder()
                .name("Employee Two")
                .email("emp2@smartcab.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.EMPLOYEE)
                .latitude(12.9352)
                .longitude(77.6245)
                .active(true)
                .build());
        emp2Token = jwtUtils.generateToken(emp2);

        admin = userRepository.save(User.builder()
                .name("Admin User")
                .email("admin@smartcab.com")
                .passwordHash(passwordEncoder.encode("adminPass123"))
                .role(Role.ADMIN)
                .active(true)
                .build());
        adminToken = jwtUtils.generateToken(admin);
    }

    private String createBookingJson(Long officeId, LocalDateTime shiftStartTime) {
        return String.format("{\"officeId\": %d, \"shiftStartTime\": \"%s\"}",
                officeId, shiftStartTime.toString());
    }

    @Test
    void testSuccessfulBooking() throws Exception {
        LocalDateTime shiftTime = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        String requestJson = createBookingJson(office.getId(), shiftTime);

        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.userId", is(emp1.getId().intValue())))
                .andExpect(jsonPath("$.officeId", is(office.getId().intValue())))
                .andExpect(jsonPath("$.pickupLatitude", is(emp1.getLatitude())))
                .andExpect(jsonPath("$.pickupLongitude", is(emp1.getLongitude())))
                .andExpect(jsonPath("$.status", is(BookingStatus.BOOKED.name())));
    }

    @Test
    void testDuplicateBookingRejected() throws Exception {
        LocalDateTime shiftTime = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        String requestJson = createBookingJson(office.getId(), shiftTime);

        // First booking succeeds
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated());

        // Second booking for same employee, office, shift is rejected
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.message", containsString("Active booking already exists")));
    }

    @Test
    void testDuplicateRequestWithSameIdempotencyKey() throws Exception {
        LocalDateTime shiftTime = LocalDateTime.of(2026, 10, 2, 9, 0, 0);
        String requestJson = createBookingJson(office.getId(), shiftTime);
        String idempotencyKey = "idemp-unique-12345";

        // Initial request
        String firstResponse = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number firstBookingId = JsonPath.read(firstResponse, "$.id");

        // Duplicate request with the same idempotency key
        String secondResponse = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number secondBookingId = JsonPath.read(secondResponse, "$.id");

        // Must return the exact same booking, not creating a duplicate
        assertEquals(firstBookingId.longValue(), secondBookingId.longValue());
        assertEquals(1, bookingRepository.count());
    }

    @Test
    void testUnauthorizedAccessToOthersBooking() throws Exception {
        LocalDateTime shiftTime = LocalDateTime.of(2026, 10, 3, 9, 0, 0);
        String requestJson = createBookingJson(office.getId(), shiftTime);

        String response = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number bookingId = JsonPath.read(response, "$.id");

        // Employee 2 attempting to view Employee 1's booking -> 403 Forbidden
        mockMvc.perform(get("/api/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + emp2Token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")));

        // Employee 2 attempting to cancel Employee 1's booking -> 403 Forbidden
        mockMvc.perform(delete("/api/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + emp2Token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)));

        // Admin can view the booking
        mockMvc.perform(get("/api/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(bookingId.intValue())));
    }

    @Test
    void testBookingCancellation() throws Exception {
        LocalDateTime shiftTime = LocalDateTime.of(2026, 10, 4, 9, 0, 0);
        String requestJson = createBookingJson(office.getId(), shiftTime);

        String response = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number bookingId = JsonPath.read(response, "$.id");

        // Employee cancels their own booking
        mockMvc.perform(delete("/api/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + emp1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(bookingId.intValue())))
                .andExpect(jsonPath("$.status", is(BookingStatus.CANCELLED.name())));
    }

    @Test
    void testCancelledBookingNoLongerConsideredActive() throws Exception {
        LocalDateTime shiftTime = LocalDateTime.of(2026, 10, 5, 9, 0, 0);
        String requestJson = createBookingJson(office.getId(), shiftTime);

        // 1. Initial booking
        String response = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number bookingId = JsonPath.read(response, "$.id");

        // 2. Cancel the booking
        mockMvc.perform(delete("/api/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + emp1Token))
                .andExpect(status().isOk());

        // 3. Re-booking for the exact same office and shift now succeeds because previous one is cancelled
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + emp1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is(BookingStatus.BOOKED.name())));
    }
}
