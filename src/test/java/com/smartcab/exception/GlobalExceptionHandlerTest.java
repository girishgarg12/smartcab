package com.smartcab.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcab.entity.*;
import com.smartcab.repository.*;
import com.smartcab.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class GlobalExceptionHandlerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CabRepository cabRepository;

    @Autowired
    private OfficeRepository officeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User admin;
    private User employee;
    private Office office;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        routeStopRepository.deleteAll();
        routeRepository.deleteAll();
        bookingRepository.deleteAll();
        cabRepository.deleteAll();
        officeRepository.deleteAll();
        userRepository.deleteAll();

        office = officeRepository.save(Office.builder()
                .name("HQ Tech Hub")
                .address("Outer Ring Road, Bangalore")
                .latitude(12.9200)
                .longitude(77.6800)
                .build());

        admin = userRepository.save(User.builder()
                .name("Admin User")
                .email("admin_err@smartcab.com")
                .passwordHash(passwordEncoder.encode("secret123"))
                .role(Role.ADMIN)
                .active(true)
                .build());
        adminToken = jwtUtils.generateToken(admin);

        employee = userRepository.save(User.builder()
                .name("Employee User")
                .email("emp_err@smartcab.com")
                .passwordHash(passwordEncoder.encode("secret123"))
                .role(Role.EMPLOYEE)
                .latitude(12.9350)
                .longitude(77.6700)
                .gender(Gender.MALE)
                .active(true)
                .build());
        employeeToken = jwtUtils.generateToken(employee);
    }

    @Test
    void testValidationErrorResponse() throws Exception {
        // Missing required fields (officeId is null, shiftStartTime is null)
        Map<String, Object> invalidPayload = Map.of(
                "pickupLatitude", 12.9350
        );

        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.path", is("/api/bookings")))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.errors", not(empty())));
    }

    @Test
    void testAuthenticationErrorResponse() throws Exception {
        Map<String, String> badLogin = Map.of(
                "email", employee.getEmail(),
                "password", "wrongPassword"
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.errorCode", is("AUTHENTICATION_ERROR")))
                .andExpect(jsonPath("$.message", is("Invalid email or password")))
                .andExpect(jsonPath("$.path", is("/api/auth/login")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void testAuthorizationErrorResponse() throws Exception {
        // Employee attempting to access Admin-only route generation endpoint
        mockMvc.perform(post("/api/routing/generate")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")))
                .andExpect(jsonPath("$.path", is("/api/routing/generate")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void testResourceNotFoundResponse() throws Exception {
        mockMvc.perform(get("/api/bookings/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.errorCode", is("RESOURCE_NOT_FOUND")))
                .andExpect(jsonPath("$.message", containsString("Booking not found with id: 999999")))
                .andExpect(jsonPath("$.path", is("/api/bookings/999999")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void testDuplicateBookingResponse() throws Exception {
        LocalDateTime shift = LocalDateTime.of(2026, 10, 10, 9, 0, 0);
        Map<String, Object> bookingPayload = Map.of(
                "officeId", office.getId(),
                "shiftStartTime", shift.toString(),
                "pickupLatitude", 12.9350,
                "pickupLongitude", 77.6700
        );

        // First booking succeeds
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingPayload)))
                .andExpect(status().isCreated());

        // Second booking for same employee and shift -> 409 Conflict + DUPLICATE_BOOKING
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.errorCode", is("DUPLICATE_BOOKING")))
                .andExpect(jsonPath("$.message", containsString("Active booking already exists")))
                .andExpect(jsonPath("$.path", is("/api/bookings")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void testInvalidRouteResponse() throws Exception {
        // Construct a route scenario where cancelling one passenger causes the remaining passenger
        // to violate max ride time constraints (~48 km away, 96 min > 60 min limit)
        User uFar = userRepository.save(User.builder()
                .name("Far User")
                .email("far_err@smartcab.com")
                .passwordHash("pwd123")
                .role(Role.EMPLOYEE)
                .active(true)
                .build());

        LocalDateTime shift = LocalDateTime.of(2026, 10, 11, 9, 0, 0);

        Booking bFar = bookingRepository.save(Booking.builder()
                .user(uFar)
                .office(office)
                .shiftStartTime(shift)
                .pickupLatitude(13.3500)
                .pickupLongitude(77.6800)
                .status(BookingStatus.ASSIGNED)
                .build());

        Booking bNormal = bookingRepository.save(Booking.builder()
                .user(employee)
                .office(office)
                .shiftStartTime(shift)
                .pickupLatitude(12.9300)
                .pickupLongitude(77.6720)
                .status(BookingStatus.ASSIGNED)
                .build());

        Cab cab = cabRepository.save(Cab.builder()
                .vehicleNumber("KA-01-FAIL-99")
                .capacity(4)
                .status(CabStatus.ASSIGNED)
                .build());

        Route route = routeRepository.save(Route.builder()
                .cab(cab)
                .office(office)
                .shiftStartTime(shift)
                .status(RouteStatus.CONFIRMED)
                .totalDistance(50.0)
                .estimatedArrivalTime(shift)
                .build());

        routeStopRepository.save(RouteStop.builder()
                .route(route)
                .booking(bFar)
                .sequence(1)
                .pickupEta(shift.minusMinutes(40))
                .rideDuration(40)
                .latitude(13.3500)
                .longitude(77.6800)
                .build());

        routeStopRepository.save(RouteStop.builder()
                .route(route)
                .booking(bNormal)
                .sequence(2)
                .pickupEta(shift.minusMinutes(15))
                .rideDuration(15)
                .latitude(12.9300)
                .longitude(77.6720)
                .build());

        // Cancel bNormal via DELETE /api/bookings/{id} -> triggers InvalidRouteException
        mockMvc.perform(delete("/api/bookings/" + bNormal.getId())
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.error", is("Unprocessable Entity")))
                .andExpect(jsonPath("$.errorCode", is("INVALID_ROUTE")))
                .andExpect(jsonPath("$.message", containsString("remaining passengers violate constraints")))
                .andExpect(jsonPath("$.path", is("/api/bookings/" + bNormal.getId())))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void testDataIntegrityViolationResponseSanitized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/cabs");

        DataIntegrityViolationException dbEx = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"cabs_vehicle_number_key\" DETAIL: Key (vehicle_number)=(KA-01-XX-9999) already exists. SQL: insert into cabs..."
        );

        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleDataIntegrityViolation(dbEx, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(409, body.getStatus());
        assertEquals("Conflict", body.getError());
        assertEquals("DATABASE_CONSTRAINT_VIOLATION", body.getErrorCode());
        assertEquals("/api/cabs", body.getPath());
        assertNotNull(body.getTimestamp());

        // Verify sensitive database info is NOT leaked to the client
        assertFalse(body.getMessage().contains("cabs_vehicle_number_key"));
        assertFalse(body.getMessage().contains("SQL"));
        assertFalse(body.getMessage().contains("insert into cabs"));
        assertTrue(body.getMessage().contains("Database constraint violation"));
    }

    @Test
    void testUnexpectedExceptionResponseSanitized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/internal/test");

        NullPointerException npe = new NullPointerException(
                "Null reference at com.smartcab.internal.SecretService.eval(SecretService.java:42) with token=secret_db_password"
        );

        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleGeneralException(npe, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(500, body.getStatus());
        assertEquals("Internal Server Error", body.getError());
        assertEquals("INTERNAL_SERVER_ERROR", body.getErrorCode());
        assertEquals("/api/internal/test", body.getPath());
        assertNotNull(body.getTimestamp());

        // Verify sensitive stack trace / internal tokens are NOT leaked to the client
        assertFalse(body.getMessage().contains("SecretService"));
        assertFalse(body.getMessage().contains("secret_db_password"));
        assertEquals("An unexpected internal server error occurred. Please contact support.", body.getMessage());
    }
}
