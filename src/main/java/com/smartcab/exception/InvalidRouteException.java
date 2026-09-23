package com.smartcab.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a proposed route or route replanning violates hard routing constraints
 * (capacity, maximum ride time, office arrival deadline, or night safety).
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class InvalidRouteException extends RuntimeException {

    public InvalidRouteException(String message) {
        super(message);
    }
}
