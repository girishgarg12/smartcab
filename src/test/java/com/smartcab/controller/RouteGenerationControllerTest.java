package com.smartcab.controller;

import com.smartcab.entity.Role;
import com.smartcab.entity.User;
import com.smartcab.repository.*;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RouteGenerationControllerTest {

    @Autowired
    private WebApplicationContext context;

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

        User admin = userRepository.save(User.builder()
                .name("Admin User")
                .email("admin_route@smartcab.com")
                .passwordHash(passwordEncoder.encode("secret123"))
                .role(Role.ADMIN)
                .active(true)
                .build());
        adminToken = jwtUtils.generateToken(admin);

        User employee = userRepository.save(User.builder()
                .name("Regular Employee")
                .email("employee_route@smartcab.com")
                .passwordHash(passwordEncoder.encode("secret123"))
                .role(Role.EMPLOYEE)
                .active(true)
                .build());
        employeeToken = jwtUtils.generateToken(employee);
    }

    @Test
    void testGenerateRoutesAdminAllowed() throws Exception {
        mockMvc.perform(post("/api/routing/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void testGenerateRoutesEmployeeForbidden() throws Exception {
        mockMvc.perform(post("/api/routing/generate")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testGenerateRoutesUnauthenticatedUnauthorized() throws Exception {
        mockMvc.perform(post("/api/routing/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
