package com.smartcab.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when an employee attempts to book an overlapping or duplicate cab shift.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateBookingException extends DuplicateResourceException {

    public DuplicateBookingException(String message) {
        super(message);
    }
}
