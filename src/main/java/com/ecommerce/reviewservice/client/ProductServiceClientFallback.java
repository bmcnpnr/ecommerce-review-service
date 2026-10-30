package com.ecommerce.reviewservice.client;

import com.ecommerce.reviewservice.dto.ProductDTO;
import com.ecommerce.reviewservice.exception.ProductServiceUnavailableException;

/**
 * Used when product-service gave no answer (see {@link ProductServiceClientFallbackFactory}). A product cannot be
 * invented, so the review is refused with a 503. Deliberately silent: it runs once per call during an outage, and
 * the breaker's state transitions plus its metrics already say what is going on.
 */
public class ProductServiceClientFallback implements ProductServiceClient {

    private final Throwable cause;

    public ProductServiceClientFallback() {
        this(null);
    }

    /** @param cause what the call failed with; carried by the exception so the handler's single log line can say why */
    public ProductServiceClientFallback(Throwable cause) {
        this.cause = cause;
    }

    @Override
    public ProductDTO getProductById(Long id) {
        throw new ProductServiceUnavailableException("Product service unavailable", cause);
    }
}
