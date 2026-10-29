package com.ecommerce.reviewservice.client;

import com.ecommerce.reviewservice.dto.ProductDTO;
import com.ecommerce.reviewservice.exception.ProductNotFoundException;
import feign.FeignException;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * A 404 from product-service is a real answer (the product does not exist), not an
 * outage, so it must not be swallowed by the generic "service unavailable" fallback.
 */
@Component
public class ProductServiceClientFallbackFactory implements FallbackFactory<ProductServiceClient> {

    @Override
    public ProductServiceClient create(Throwable cause) {
        if (cause instanceof FeignException.NotFound) {
            return new ProductServiceClient() {
                @Override
                public ProductDTO getProductById(Long id) {
                    throw new ProductNotFoundException("Product not found with id: " + id);
                }
            };
        }
        return new ProductServiceClientFallback();
    }
}
