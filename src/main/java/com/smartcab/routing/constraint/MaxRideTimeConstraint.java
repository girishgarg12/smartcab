package com.smartcab.routing.constraint;

import com.smartcab.routing.optimizer.OptimizedStop;
import org.springframework.stereotype.Component;

/**
 * Hard constraint ensuring that no employee's in-cab travel time exceeds the maximum permissible limit.
 * <p>
 * This constraint is unbreakable.
 * </p>
 */
@Component
public class MaxRideTimeConstraint implements RouteConstraint {

    public static final String NAME = "MaxRideTimeConstraint";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ConstraintValidationResult validate(RouteCandidate candidate) {
        if (candidate == null) {
            return ConstraintValidationResult.invalid(NAME, "Candidate must not be null");
        }

        if (candidate.stops() == null || candidate.stops().isEmpty()) {
            return ConstraintValidationResult.valid(NAME);
        }

        int maxAllowedMinutes = candidate.maxRideTimeMinutes();

        for (OptimizedStop stop : candidate.stops()) {
            if (stop.rideDurationMinutes() > maxAllowedMinutes) {
                Long bookingId = stop.booking() != null ? stop.booking().getId() : null;
                return ConstraintValidationResult.invalid(
                        NAME,
                        String.format(
                                "Stop sequence %d (booking ID: %s) ride time (%d mins) exceeds maximum allowed limit (%d mins)",
                                stop.sequence(),
                                bookingId != null ? bookingId : "N/A",
                                stop.rideDurationMinutes(),
                                maxAllowedMinutes
                        )
                );
            }
        }

        return ConstraintValidationResult.valid(NAME);
    }
}
