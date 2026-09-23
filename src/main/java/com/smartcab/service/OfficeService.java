package com.smartcab.service;

import com.smartcab.dto.OfficeRequest;
import com.smartcab.dto.OfficeResponse;
import com.smartcab.entity.Office;
import com.smartcab.exception.ResourceNotFoundException;
import com.smartcab.repository.OfficeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OfficeService {

    private final OfficeRepository officeRepository;

    @Transactional
    public OfficeResponse createOffice(OfficeRequest request) {
        Office office = Office.builder()
                .name(request.getName().trim())
                .address(request.getAddress() != null ? request.getAddress().trim() : null)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .build();

        Office saved = officeRepository.save(office);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<OfficeResponse> getAllOffices() {
        return officeRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OfficeResponse getOfficeById(Long id) {
        Office office = officeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Office not found with id: " + id));
        return mapToResponse(office);
    }

    public OfficeResponse mapToResponse(Office office) {
        return OfficeResponse.builder()
                .id(office.getId())
                .name(office.getName())
                .address(office.getAddress())
                .latitude(office.getLatitude())
                .longitude(office.getLongitude())
                .build();
    }
}
