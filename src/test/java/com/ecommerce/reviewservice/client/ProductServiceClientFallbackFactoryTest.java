package com.ecommerce.reviewservice.client;

import com.ecommerce.reviewservice.exception.ProductNotFoundException;
import com.ecommerce.reviewservice.exception.ProductServiceErrorException;
import com.ecommerce.reviewservice.exception.ProductServiceUnavailableException;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each kind of product-service failure becomes once the circuit breaker hands it to the fallback: the
 * difference between "no such product" (404), "no answer" (503), "unusable answer" (502) and "our bug" (500).
 */
class ProductServiceClientFallbackFactoryTest {

    private final ProductServiceClientFallbackFactory factory = new ProductServiceClientFallbackFactory();

    private static final Request REQUEST = Request.create(Request.HttpMethod.GET, "http://product-service/api/v1/products/7",
            Map.<String, Collection<String>>of(), null, StandardCharsets.UTF_8, new RequestTemplate());

    private static FeignException answered(int status) {
        return FeignException.errorStatus("ProductServiceClient#getProductById(Long)",
                Response.builder().status(status).reason("x").request(REQUEST).headers(Map.of()).build());
    }

    private Throwable failureFor(Throwable cause) {
        return catchThrowable(() -> factory.create(cause).getProductById(7L));
    }

    private static Throwable catchThrowable(Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            return t;
        }
        return null;
    }

    @Test
    void aNotFound_isAnAnswer_andBecomesAProductNotFound() {
        assertThat(failureFor(answered(404)))
                .isExactlyInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product not found with id: 7");
    }

    @Test
    void noAnswer_isAnOutage() {
        RetryableException refused = new RetryableException(-1, "connect refused", Request.HttpMethod.GET,
                new ConnectException("Connection refused"), (Long) null, REQUEST);
        RetryableException readTimeout = new RetryableException(-1, "read timed out", Request.HttpMethod.GET,
                new SocketTimeoutException("Read timed out"), (Long) null, REQUEST);
        CircuitBreaker open = CircuitBreaker.ofDefaults("product-service");

        assertThat(failureFor(refused)).isExactlyInstanceOf(ProductServiceUnavailableException.class);
        assertThat(failureFor(readTimeout)).isExactlyInstanceOf(ProductServiceUnavailableException.class);
        assertThat(failureFor(new TimeoutException("did not complete within 20000ms"))).isExactlyInstanceOf(ProductServiceUnavailableException.class);
        assertThat(failureFor(CallNotPermittedException.createCallNotPermittedException(open)))
                .as("an open breaker").isExactlyInstanceOf(ProductServiceUnavailableException.class);
        assertThat(failureFor(answered(503))).as("also the load balancer's 'no instance' answer").isExactlyInstanceOf(ProductServiceUnavailableException.class);
        assertThat(failureFor(answered(504))).isExactlyInstanceOf(ProductServiceUnavailableException.class);
        assertThat(failureFor(new DecodeException(200, "reset while reading the body", REQUEST, new IOException("Connection reset"))))
                .as("a connection lost half way through the body").isExactlyInstanceOf(ProductServiceUnavailableException.class);
    }

    @Test
    void anUnusableAnswer_isABadGateway_carryingOnlyTheStatus() {
        assertThat(failureFor(answered(500))).isExactlyInstanceOf(ProductServiceErrorException.class)
                .hasMessage("product-service answered with status 500")
                .extracting(t -> ((ProductServiceErrorException) t).getUpstreamStatus()).isEqualTo(500);
        assertThat(failureFor(answered(502))).isExactlyInstanceOf(ProductServiceErrorException.class);
        assertThat(failureFor(answered(400))).as("our request was rejected").isExactlyInstanceOf(ProductServiceErrorException.class);
        assertThat(failureFor(new DecodeException(200, "not json", REQUEST, new IllegalStateException("bad token"))))
                .isExactlyInstanceOf(ProductServiceErrorException.class);
    }

    @Test
    void anythingElse_isABugHere_andIsNotDisguised() {
        IllegalStateException bug = new IllegalStateException("bug");

        assertThat(failureFor(bug)).isSameAs(bug);
        assertThat(failureFor(new Exception("checked")))
                .isExactlyInstanceOf(IllegalStateException.class).hasCauseInstanceOf(Exception.class);
    }

    /**
     * If the breaker ever runs the call on another thread again, the fallback gets the Feign exception wrapped in an
     * ExecutionException; a 404 must still be a 404 and an outage still an outage.
     */
    @Test
    void wrappersFromAnotherThread_doNotHideTheRealCause() {
        assertThat(failureFor(new java.util.concurrent.ExecutionException(answered(404)))).isExactlyInstanceOf(ProductNotFoundException.class);
        assertThat(failureFor(new java.util.concurrent.CompletionException(new java.util.concurrent.ExecutionException(answered(503)))))
                .isExactlyInstanceOf(ProductServiceUnavailableException.class);
        assertThat(failureFor(new java.util.concurrent.ExecutionException(answered(500)))).isExactlyInstanceOf(ProductServiceErrorException.class);
    }

    @Test
    void anOutageCarriesItsCause_soTheSingleLogLineCanSayWhy() {
        RetryableException refused = new RetryableException(-1, "refused", Request.HttpMethod.GET, new ConnectException("Connection refused"), (Long) null, REQUEST);

        assertThat(failureFor(refused)).isExactlyInstanceOf(ProductServiceUnavailableException.class).hasCause(refused);
    }

    @Test
    void theFallbackNeverReturnsAProduct() {
        assertThatThrownBy(() -> factory.create(answered(503)).getProductById(1L)).isInstanceOf(ProductServiceUnavailableException.class);
        assertThatThrownBy(() -> factory.create(answered(500)).getProductById(1L)).isInstanceOf(ProductServiceErrorException.class);
        assertThatThrownBy(() -> factory.create(answered(404)).getProductById(1L)).isInstanceOf(ProductNotFoundException.class);
    }
}
