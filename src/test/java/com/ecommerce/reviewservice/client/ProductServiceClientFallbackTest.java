package com.ecommerce.reviewservice.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductServiceClientFallbackTest {

    @Test
    void getProductById_alwaysFailsFast_withCircuitOpenMessage() {
        ProductServiceClient fallback = new ProductServiceClientFallback();

        assertThatThrownBy(() -> fallback.getProductById(1L))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Product service unavailable - circuit breaker open");
    }

    @Test
    void getProductById_neverReturnsAPlaceholderProduct_soReviewsCannotBeCreatedBlind() {
        // A fallback that returned a dummy ProductDTO would let createReview store
        // reviews for products that may not exist while product-service is down.
        assertThatThrownBy(() -> new ProductServiceClientFallback().getProductById(null))
                .isInstanceOf(RuntimeException.class);
    }
}
