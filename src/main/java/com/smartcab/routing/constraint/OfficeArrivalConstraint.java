package com.smartcab.routing.constraint;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Hard constraint ensuring that the cab reaches the office destination at or before
 * the employee shift start time.
 */
@Component
public class OfficeArrivalConstraint implements RouteConstraint {

    public static final String NAME = "OfficeArrivalConstraint";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ConstraintValidationResult validate(RouteCandidate candidate) {
        if (candidate == null) {
            return ConstraintValidationResult.invalid(NAME, "Candidate must not be null");
        }

        LocalDateTime officeEta = candidate.officeEta();
        LocalDateTime shiftStartTime = candidate.shiftStartTime();

        if (officeEta == null || shiftStartTime == null) {
            return ConstraintValidationResult.invalid(NAME, "Office ETA and shift start time must not be null");
        }

        if (officeEta.isAfter(shiftStartTime)) {
            return ConstraintValidationResult.invalid(
                    NAME,
                    String.format("Office arrival time (%s) is after shift start time (%s)", officeEta, shiftStartTime)
            );
        }

        return ConstraintValidationResult.valid(NAME);
    }
}
