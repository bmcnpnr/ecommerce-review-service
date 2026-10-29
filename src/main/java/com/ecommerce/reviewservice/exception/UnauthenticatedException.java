package com.ecommerce.reviewservice.exception;

/** No (or a blank) X-Username: the caller is not authenticated (401). */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException(String message) { super(message); }
}
