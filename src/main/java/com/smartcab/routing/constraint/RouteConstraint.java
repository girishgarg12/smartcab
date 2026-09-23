package com.smartcab.routing.constraint;

/**
 * Contract for routing constraints that determine whether a proposed route candidate is feasible.
 */
public interface RouteConstraint {

    /**
     * Identifies the constraint rule name.
     *
     * @return Human-readable constraint identifier
     */
    String getName();

    /**
     * Validates whether the given route candidate satisfies this constraint.
     *
     * @param candidate Proposed route candidate and context
     * @return Validation result containing pass/fail status and explanation
     */
    ConstraintValidationResult validate(RouteCandidate candidate);
}
