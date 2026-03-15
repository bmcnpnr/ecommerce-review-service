package com.ecommerce.reviewservice.client;

import com.ecommerce.reviewservice.dto.ProductDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "product-service", fallback = ProductServiceClientFallback.class)
public interface ProductServiceClient {

    @GetMapping("/api/v1/products/{id}")
    ProductDTO getProductById(@PathVariable Long id);
}
