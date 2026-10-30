package com.ecommerce.reviewservice.exception;

/**
 * product-service answered, but not with something usable: a 5xx other than 503/504, an unexpected 4xx or a body
 * that cannot be decoded (502). {@code upstreamStatus} is the HTTP status it sent, or -1 when there was none.
 */
public class ProductServiceErrorException extends RuntimeException {

    private final int upstreamStatus;

    public ProductServiceErrorException(String message, int upstreamStatus) {
        super(message);
        this.upstreamStatus = upstreamStatus;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
