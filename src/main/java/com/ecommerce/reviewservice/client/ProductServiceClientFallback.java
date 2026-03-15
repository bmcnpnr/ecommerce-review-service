package com.ecommerce.reviewservice.client;

import com.ecommerce.reviewservice.dto.ProductDTO;
import org.springframework.stereotype.Component;

@Component
public class ProductServiceClientFallback implements ProductServiceClient {

    @Override
    public ProductDTO getProductById(Long id) {
        throw new RuntimeException("Product service unavailable - circuit breaker open");
    }
}
