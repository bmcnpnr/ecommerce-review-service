package com.ecommerce.reviewservice.exception;

public class DuplicateReviewException extends RuntimeException {
    public DuplicateReviewException(String message) { super(message); }
}
