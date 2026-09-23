package com.smartcab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcab.dto.LoginRequest;
import com.smartcab.dto.RegisterRequest;
import com.smartcab.entity.Gender;
import com.smartcab.entity.Role;
import com.smartcab.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class AuthControllerTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.smartcab.repository.BookingRepository bookingRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        bookingRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testSuccessfulRegistration() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .name("Alice Smith")
                .email("alice@smartcab.com")
                .password("securePassword123")
                .gender(Gender.FEMALE)
                .latitude(12.9716)
                .longitude(77.5946)
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("Alice Smith")))
                .andExpect(jsonPath("$.email", is("alice@smartcab.com")))
                .andExpect(jsonPath("$.role", is(Role.EMPLOYEE.name())))
                .andExpect(jsonPath("$.gender", is(Gender.FEMALE.name())))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void testDuplicateRegistration() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .name("Bob Jones")
                .email("bob@smartcab.com")
                .password("password123")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Attempt to register with the same email
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Conflict")))
                .andExpect(jsonPath("$.message", containsString("already registered")));
    }

    @Test
    void testSuccessfulLogin() throws Exception {
        // Register user first
        RegisterRequest registerRequest = RegisterRequest.builder()
                .name("Charlie Brown")
                .email("charlie@smartcab.com")
                .password("correctPassword")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Attempt login
        LoginRequest loginRequest = LoginRequest.builder()
                .email("charlie@smartcab.com")
                .password("correctPassword")
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyOrNullString())))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is("charlie@smartcab.com")))
                .andExpect(jsonPath("$.user.name", is("Charlie Brown")))
                .andExpect(jsonPath("$.user.role", is(Role.EMPLOYEE.name())))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void testLoginWithInvalidPassword() throws Exception {
        // Register user first
        RegisterRequest registerRequest = RegisterRequest.builder()
                .name("David Miller")
                .email("david@smartcab.com")
                .password("correctPassword")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Attempt login with wrong password
        LoginRequest loginRequest = LoginRequest.builder()
                .email("david@smartcab.com")
                .password("wrongPassword")
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", is("Invalid email or password")));
    }

    @Test
    void testProtectedEndpointWithoutJwt() throws Exception {
        // Any endpoint other than /api/auth/** or /actuator/health is protected
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    void testProtectedEndpointWithValidJwt() throws Exception {
        // Register and login to obtain JWT
        RegisterRequest registerRequest = RegisterRequest.builder()
                .name("Eve Adams")
                .email("eve@smartcab.com")
                .password("secret123")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = LoginRequest.builder()
                .email("eve@smartcab.com")
                .password("secret123")
                .build();

        String responseJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(responseJson).get("token").asText();

        // Access protected endpoint with Bearer token (should be authenticated, not 401 Unauthorized)
        mockMvc.perform(get("/api/bookings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(not(401)));
    }
}
