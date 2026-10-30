package com.ecommerce.reviewservice.exception;

/**
 * product-service gave no answer: connection refused or timed out, no instance registered, or the circuit
 * breaker is open (503, L4). Not a bug and not worth a stack trace.
 */
public class ProductServiceUnavailableException extends RuntimeException {
    public ProductServiceUnavailableException(String message) { super(message); }

    /** {@code cause} is the failure behind the outage (connection refused, an open breaker, ...), for the one throttled log line. */
    public ProductServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
