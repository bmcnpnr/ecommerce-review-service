package com.ecommerce.reviewservice.contract;

import au.com.dius.pact.consumer.dsl.LambdaDsl;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit.MockServerConfig;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.config.FeignConfig;
import com.ecommerce.reviewservice.dto.CreateReviewRequest;
import com.ecommerce.reviewservice.dto.ReviewDTO;
import com.ecommerce.reviewservice.model.Review;
import com.ecommerce.reviewservice.model.ReviewStatus;
import com.ecommerce.reviewservice.repository.ReviewRepository;
import com.ecommerce.reviewservice.repository.ReviewVoteRepository;
import com.ecommerce.reviewservice.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumer side of the contract between review-service and product-service.
 *
 * <p>review-service calls product-service through {@link ProductServiceClient}
 * (Feign, resolved via Eureka as {@code product-service}) for exactly one thing:
 * {@link ReviewService#createReview} asks for the product to check that it exists
 * before a review is stored. The response body is discarded, so the contract pins
 * only the status codes review-service reacts to (200 = exists, 404 = does not),
 * plus {@code id} as the minimal body shape. These tests run the <em>real</em>
 * Feign client — the production {@link FeignConfig} and Boot's Jackson/converter
 * setup — against a Pact mock server playing product-service, driven through
 * {@link ReviewService}.
 *
 * <p>Running this class writes {@code target/pacts/review-service-product-service.json}.
 * That file is copied into product-service's {@code src/test/resources/pacts/} (see
 * {@code ecommerce-platform/sync-pacts.sh}) where {@code ProductServiceProviderPactTest}
 * replays every interaction against the real product-service.
 */
@SpringBootTest(
        classes = ProductServiceConsumerPactTest.FeignOnlyConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Point the production Feign client (lb://product-service) at the Pact mock
                // server instead of the load balancer. The port is fixed because the Spring
                // context is built before Pact starts the mock server for each test.
                "spring.cloud.openfeign.client.config.product-service.url=http://localhost:" + ProductServiceConsumerPactTest.MOCK_PORT,
                "spring.cloud.openfeign.client.config.default.connect-timeout=2000",
                "spring.cloud.openfeign.client.config.default.read-timeout=10000",
                // Pact restarts its mock server for every test method; a pooled keep-alive
                // connection from the previous test would be stale. Expire pooled
                // connections immediately so each call opens a fresh one (test-only).
                "spring.cloud.openfeign.httpclient.time-to-live=1",
                "spring.cloud.openfeign.httpclient.time-to-live-unit=MILLISECONDS",
                "spring.cloud.discovery.enabled=false",
                "eureka.client.enabled=false"
        })
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "product-service", pactVersion = PactSpecVersion.V4)
@MockServerConfig(port = ProductServiceConsumerPactTest.MOCK_PORT)
class ProductServiceConsumerPactTest {

    static final String CONSUMER = "review-service";
    static final String MOCK_PORT = "18087";

    /**
     * Just enough of the application to build the real Feign client: Spring Cloud
     * OpenFeign, Boot's HTTP message converters and Jackson, plus the service's own
     * {@link FeignConfig}. No JPA or Eureka.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @EnableFeignClients(clients = ProductServiceClient.class)
    @Import({FeignConfig.class, ReviewService.class})
    static class FeignOnlyConfig {
    }

    @Autowired
    private ReviewService reviewService;

    @MockitoBean
    private ReviewRepository reviewRepository;

    @MockitoBean
    private ReviewVoteRepository reviewVoteRepository;

    // ───────────────────────────── pacts ─────────────────────────────

    @Pact(consumer = CONSUMER)
    V4Pact existingProductCanBeReviewed(PactDslWithProvider builder) {
        return builder
                .given("product 1 exists")
                .uponReceiving("a request for product 1")
                    .method("GET")
                    .path("/api/v1/products/1")
                .willRespondWith()
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(LambdaDsl.newJsonBody(product -> product
                            .integerType("id", 1)).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknownProductIsNotFound(PactDslWithProvider builder) {
        return builder
                .given("product 999 does not exist")
                .uponReceiving("a request for product 999")
                    .method("GET")
                    .path("/api/v1/products/999")
                .willRespondWith()
                    .status(404)
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(LambdaDsl.newJsonBody(error -> error
                            .integerType("status", 404)
                            .stringType("error", "Not Found")
                            .stringType("message", "Product not found with id: 999")).build())
                .toPact(V4Pact.class);
    }

    // ───────────────────────────── tests ─────────────────────────────

    @Test
    @PactTestFor(pactMethod = "existingProductCanBeReviewed")
    void createReview_checksTheProductExists_thenStoresTheReview() {
        when(reviewRepository.findByProductIdAndCustomerId(1L, "alice")).thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(11L);
            return r;
        });

        ReviewDTO dto = reviewService.createReview(request(1L), "alice");

        assertThat(dto.getId()).isEqualTo(11L);
        assertThat(dto.getProductId()).isEqualTo(1L);
        assertThat(dto.getCustomerId()).isEqualTo("alice");
        assertThat(dto.getStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    @PactTestFor(pactMethod = "unknownProductIsNotFound")
    void createReview_unknownProduct_isRejectedAndNothingIsStored() {
        // ReviewService wraps any client failure in a RuntimeException; its
        // GlobalExceptionHandler has no mapping for it, so callers see a 500 today.
        assertThatThrownBy(() -> reviewService.createReview(request(999L), "alice"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Product 999 not found or product service unavailable");
        verify(reviewRepository, never()).save(any());
    }

    private static CreateReviewRequest request(long productId) {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setProductId(productId);
        request.setRating((short) 5);
        request.setTitle("Great mouse");
        request.setBody("Comfortable, precise and the battery lasts for weeks.");
        return request;
    }
}
