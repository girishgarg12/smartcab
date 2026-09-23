package com.smartcab.routing.constraint;

import com.smartcab.entity.Booking;
import com.smartcab.entity.Gender;
import com.smartcab.routing.optimizer.OptimizedStop;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

/**
 * Route constraint enforcing the night safety rule for female employees.
 *
 * <h3>Case Study Rule:</h3>
 * <p>
 * <i>"A woman employee must not be the first pickup or the last drop alone during night hours."</i>
 * </p>
 *
 * <h3>Operational Interpretation of "Alone":</h3>
 * <ul>
 *   <li><b>Inbound Pickup Journeys (Home &rarr; Office):</b>
 *     The cab starts empty from the depot/driver. The first boarded passenger (stop sequence 1)
 *     travels unaccompanied by any colleagues until the second passenger is picked up (or until
 *     the office is reached in a single-passenger trip).
 *     <p>
 *     If that first boarded passenger is a woman ({@link Gender#FEMALE}) during night hours,
 *     she is considered "alone with the driver" on leg 1. This violates the night safety rule
 *     unless a security escort guard is present on board from the start.
 *     </p>
 *     <p>
 *     Subsequent passengers board a cab that is already occupied by earlier passengers, so they
 *     are never alone. At the final destination (the office), all passengers disembark together.
 *     </p>
 *   </li>
 *   <li><b>Outbound Drop-off Journeys (Office &rarr; Home):</b>
 *     In an evening outbound trip from office to residential drops, passengers disembark sequentially.
 *     The final passenger remaining in the vehicle travels alone with the driver on the final leg.
 *     A female employee must not be the last passenger dropped off alone during night hours unless
 *     accompanied by an escort guard.
 *   </li>
 *   <li><b>Escort Guard Resolution:</b>
 *     When an escort guard is present on board ({@code candidate.hasEscortGuard() == true}), the
 *     female employee is accompanied by security personnel, satisfying the safety rule.
 *     The guard occupies one passenger seat, which is accounted for in {@link CapacityConstraint}.
 *   </li>
 * </ul>
 *
 * <h3>Night Hours Definition:</h3>
 * <p>
 * Configurable with default values from <b>20:00 (8:00 PM)</b> to <b>06:00 (6:00 AM)</b> next day.
 * </p>
 */
@Component
public class NightSafetyConstraint implements RouteConstraint {

    public static final String NAME = "NightSafetyConstraint";
    public static final LocalTime DEFAULT_NIGHT_START = LocalTime.of(20, 0);
    public static final LocalTime DEFAULT_NIGHT_END = LocalTime.of(6, 0);

    private final LocalTime nightStart;
    private final LocalTime nightEnd;

    public NightSafetyConstraint() {
        this(DEFAULT_NIGHT_START, DEFAULT_NIGHT_END);
    }

    public NightSafetyConstraint(LocalTime nightStart, LocalTime nightEnd) {
        this.nightStart = nightStart != null ? nightStart : DEFAULT_NIGHT_START;
        this.nightEnd = nightEnd != null ? nightEnd : DEFAULT_NIGHT_END;
    }

    public LocalTime getNightStart() {
        return nightStart;
    }

    public LocalTime getNightEnd() {
        return nightEnd;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ConstraintValidationResult validate(RouteCandidate candidate) {
        if (candidate == null) {
            return ConstraintValidationResult.invalid(NAME, "Candidate must not be null");
        }

        if (candidate.hasEscortGuard()) {
            return ConstraintValidationResult.valid(NAME);
        }

        List<OptimizedStop> stops = candidate.stops();
        if (stops == null || stops.isEmpty()) {
            return ConstraintValidationResult.valid(NAME);
        }

        OptimizedStop firstStop = stops.get(0);
        Booking firstBooking = firstStop.booking();

        boolean isNight = isNightTime(firstStop.pickupEta().toLocalTime())
                || (candidate.shiftStartTime() != null && isNightTime(candidate.shiftStartTime().toLocalTime()));

        if (!isNight) {
            return ConstraintValidationResult.valid(NAME);
        }

        if (firstBooking != null && firstBooking.getUser() != null
                && firstBooking.getUser().getGender() == Gender.FEMALE) {
            String userName = firstBooking.getUser().getName() != null
                    ? firstBooking.getUser().getName()
                    : "User ID " + firstBooking.getUser().getId();

            return ConstraintValidationResult.invalid(
                    NAME,
                    String.format(
                            "Night safety violation: Woman employee (%s) cannot be the first pickup alone during night hours (%s - %s) without an escort guard",
                            userName, nightStart, nightEnd
                    )
            );
        }

        return ConstraintValidationResult.valid(NAME);
    }

    /**
     * Checks if a given time falls within the configured night hours window.
     *
     * @param time Time to check
     * @return true if the time falls in night hours, false otherwise
     */
    public boolean isNightTime(LocalTime time) {
        if (time == null) {
            return false;
        }

        if (nightStart.isAfter(nightEnd)) {
            return !time.isBefore(nightStart) || !time.isAfter(nightEnd);
        } else {
            return !time.isBefore(nightStart) && !time.isAfter(nightEnd);
        }
    }
}
