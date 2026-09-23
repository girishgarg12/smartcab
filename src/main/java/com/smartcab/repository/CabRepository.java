package com.smartcab.repository;

import com.smartcab.entity.Cab;
import com.smartcab.entity.CabStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CabRepository extends JpaRepository<Cab, Long> {
    List<Cab> findByStatus(CabStatus status);
    boolean existsByVehicleNumber(String vehicleNumber);
    java.util.Optional<Cab> findByVehicleNumber(String vehicleNumber);
}
