package com.ecommerce.reviewservice.exception;

public class UnauthorizedReviewAccessException extends RuntimeException {
    public UnauthorizedReviewAccessException(String message) { super(message); }
}
