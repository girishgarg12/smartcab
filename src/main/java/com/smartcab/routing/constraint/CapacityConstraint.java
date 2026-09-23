package com.smartcab.routing.constraint;

import org.springframework.stereotype.Component;

/**
 * Hard constraint ensuring that passenger count does not exceed cab capacity.
 * <p>
 * This constraint is unbreakable.
 * </p>
 */
@Component
public class CapacityConstraint implements RouteConstraint {

    public static final String NAME = "CapacityConstraint";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ConstraintValidationResult validate(RouteCandidate candidate) {
        if (candidate == null) {
            return ConstraintValidationResult.invalid(NAME, "Candidate must not be null");
        }

        int passengerCount = candidate.bookings() != null ? candidate.bookings().size() : 0;
        int guardCount = candidate.hasEscortGuard() ? 1 : 0;
        int totalOccupants = passengerCount + guardCount;
        int capacity = candidate.cabCapacity();

        if (totalOccupants > capacity) {
            return ConstraintValidationResult.invalid(
                    NAME,
                    String.format("Total occupants (%d = %d passengers + %d guard) exceeds maximum cab capacity (%d)",
                            totalOccupants, passengerCount, guardCount, capacity)
            );
        }

        return ConstraintValidationResult.valid(NAME);
    }
}
