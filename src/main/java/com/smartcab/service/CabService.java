package com.smartcab.service;

import com.smartcab.dto.CabRequest;
import com.smartcab.dto.CabResponse;
import com.smartcab.entity.Cab;
import com.smartcab.entity.CabStatus;
import com.smartcab.exception.DuplicateResourceException;
import com.smartcab.exception.ResourceNotFoundException;
import com.smartcab.repository.CabRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CabService {

    private final CabRepository cabRepository;

    @Transactional
    public CabResponse createCab(CabRequest request) {
        String normalizedVehicleNumber = request.getVehicleNumber().trim().toUpperCase();

        if (cabRepository.existsByVehicleNumber(normalizedVehicleNumber)) {
            throw new DuplicateResourceException("Vehicle number already exists: " + normalizedVehicleNumber);
        }

        if (request.getCapacity() != 4 && request.getCapacity() != 6) {
            throw new IllegalArgumentException("Cab capacity must be either 4 or 6");
        }

        CabStatus status = request.getStatus() != null ? request.getStatus() : CabStatus.AVAILABLE;

        Cab cab = Cab.builder()
                .vehicleNumber(normalizedVehicleNumber)
                .capacity(request.getCapacity())
                .status(status)
                .build();

        Cab saved = cabRepository.save(cab);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CabResponse> getAllCabs() {
        return cabRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CabResponse getCabById(Long id) {
        Cab cab = cabRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cab not found with id: " + id));
        return mapToResponse(cab);
    }

    public CabResponse mapToResponse(Cab cab) {
        return CabResponse.builder()
                .id(cab.getId())
                .vehicleNumber(cab.getVehicleNumber())
                .capacity(cab.getCapacity())
                .status(cab.getStatus())
                .build();
    }
}
