package com.smartcab.controller;

import com.smartcab.dto.CabRequest;
import com.smartcab.dto.CabResponse;
import com.smartcab.service.CabService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cabs")
@RequiredArgsConstructor
public class CabController {

    private final CabService cabService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CabResponse> createCab(@Valid @RequestBody CabRequest request) {
        CabResponse response = cabService.createCab(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<List<CabResponse>> getAllCabs() {
        return ResponseEntity.ok(cabService.getAllCabs());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<CabResponse> getCabById(@PathVariable Long id) {
        return ResponseEntity.ok(cabService.getCabById(id));
    }
}
