package com.ecommerce.reviewservice.client;

import com.ecommerce.reviewservice.dto.ProductDTO;
import com.ecommerce.reviewservice.exception.ProductNotFoundException;
import com.ecommerce.reviewservice.exception.ProductServiceErrorException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Turns every product-service failure into the exception that tells the caller what it means:
 * <ul>
 *   <li>404: a real answer (the product does not exist), passed on as a 404 and not counted by the breaker;</li>
 *   <li>no answer (connection refused or timed out, no instance, 503/504, breaker open, time limit): 503;</li>
 *   <li>an answer that is unusable (other 5xx, unexpected 4xx, undecodable body): 502;</li>
 *   <li>anything else is a bug here and is rethrown as it is.</li>
 * </ul>
 * Logs nothing: this runs once per call during an outage (L5).
 */
@Component
public class ProductServiceClientFallbackFactory implements FallbackFactory<ProductServiceClient> {

    @Override
    public ProductServiceClient create(Throwable thrown) {
        Throwable cause = unwrap(thrown);
        if (cause instanceof FeignException.NotFound) {
            return new ProductServiceClient() {
                @Override
                public ProductDTO getProductById(Long id) {
                    throw new ProductNotFoundException("Product not found with id: " + id);
                }
            };
        }
        if (isNoAnswer(cause)) {
            return new ProductServiceClientFallback(cause);
        }
        RuntimeException failure = cause instanceof FeignException feign
                ? new ProductServiceErrorException("product-service answered with status " + feign.status(), feign.status())
                : cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        return new ProductServiceClient() {
            @Override
            public ProductDTO getProductById(Long id) {
                throw failure;
            }
        };
    }

    /**
     * Strips the wrappers a call picks up when it runs on another thread or through reflection, so that the checks
     * above see the Feign exception itself. With the breaker's thread pool off (application.yaml) there are none, but
     * a setting flipped back must not turn every 404 into a 503.
     */
    static Throwable unwrap(Throwable thrown) {
        Throwable t = thrown;
        for (int depth = 0; depth < 8 && t.getCause() != null && t.getCause() != t
                && (t instanceof ExecutionException || t instanceof CompletionException
                    || t instanceof UndeclaredThrowableException || t instanceof InvocationTargetException); depth++) {
            t = t.getCause();
        }
        return t;
    }

    /** True when the cause chain says product-service never produced a response we could use as an answer. */
    static boolean isNoAnswer(Throwable cause) {
        int depth = 0;
        for (Throwable t = cause; t != null && depth < 16; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof CallNotPermittedException || t instanceof TimeoutException || t instanceof IOException) {
                return true;
            }
            // -1: Feign's own status for "no response" (RetryableException); 503: the load balancer's
            // "no instance" answer or an overloaded peer; 504: a proxy's timeout.
            if (t instanceof FeignException feign && (feign.status() == -1 || feign.status() == 503 || feign.status() == 504)) {
                return true;
            }
        }
        return false;
    }
}
