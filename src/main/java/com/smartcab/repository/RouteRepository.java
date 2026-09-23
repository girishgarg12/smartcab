package com.smartcab.repository;

import com.smartcab.entity.Route;
import com.smartcab.entity.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {
    List<Route> findByStatus(RouteStatus status);
}
