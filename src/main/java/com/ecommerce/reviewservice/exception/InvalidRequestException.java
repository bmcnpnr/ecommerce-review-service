package com.ecommerce.reviewservice.exception;

/** A well-formed request carrying an unacceptable value, for example an over-long identity header (400). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) { super(message); }
}
