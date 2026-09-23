package com.smartcab.routing.constraint;

/**
 * Result object describing the outcome of a route constraint evaluation.
 *
 * @param isValid        True if constraint was satisfied, false otherwise
 * @param constraintName Name of the constraint evaluated
 * @param failureReason  Descriptive explanation if constraint failed, or null if valid
 */
public record ConstraintValidationResult(
        boolean isValid,
        String constraintName,
        String failureReason
) {
    public static ConstraintValidationResult valid(String constraintName) {
        return new ConstraintValidationResult(true, constraintName, null);
    }

    public static ConstraintValidationResult invalid(String constraintName, String failureReason) {
        return new ConstraintValidationResult(false, constraintName, failureReason);
    }
}
