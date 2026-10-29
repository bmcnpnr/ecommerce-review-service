package com.ecommerce.reviewservice.integration;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole service over HTTP on PostgreSQL (production Flyway schema), with
 * product-service played by WireMock so the real Feign client — FeignConfig's
 * retryer and converters, Boot's Jackson — is on the path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.cloud.autoconfigure.LifecycleMvcEndpointAutoConfiguration",
        "management.tracing.sampling.probability=0.0",
        "spring.cloud.openfeign.client.config.default.read-timeout=3000"
})
class ReviewApiIT extends PostgresIntegrationTestSupport {

    static final WireMockServer PRODUCT_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        PRODUCT_SERVICE.start();
    }

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.product-service.url", PRODUCT_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        PRODUCT_SERVICE.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductServiceClient productServiceClient;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE review_votes, reviews RESTART IDENTITY CASCADE");
        PRODUCT_SERVICE.resetAll();
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void reviewFlow_createDuplicateModerateVoteSummary() {
        productExists(1L);

        // create (alice, bob, carol) — reviews start PENDING
        long aliceReview = createReview(1L, "alice", 5);
        long bobReview = createReview(1L, "bob", 4);
        createReview(1L, "carol", 2);
        PRODUCT_SERVICE.verify(3, getRequestedFor(urlEqualTo("/api/v1/products/1")));

        // duplicate rejected
        client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L, 3))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.message").isEqualTo("Customer alice has already reviewed product 1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isEqualTo(3);

        // nothing approved yet
        client.get().uri("/api/v1/reviews/product/1").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
        client.get().uri("/api/v1/reviews/product/1?approvedOnly=false").header("X-User-Role", "ADMIN").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(3);

        // moderate two of them
        moderate(aliceReview, "APPROVED");
        moderate(bobReview, "APPROVED");

        // votes: bob helpful, bob changes his mind, carol helpful, dave helpful
        vote(aliceReview, "bob", true).jsonPath("$.helpfulVotes").isEqualTo(1).jsonPath("$.unhelpfulVotes").isEqualTo(0);
        vote(aliceReview, "bob", false).jsonPath("$.helpfulVotes").isEqualTo(0).jsonPath("$.unhelpfulVotes").isEqualTo(1);
        vote(aliceReview, "carol", true).jsonPath("$.helpfulVotes").isEqualTo(1).jsonPath("$.unhelpfulVotes").isEqualTo(1);
        vote(aliceReview, "dave", true).jsonPath("$.helpfulVotes").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_votes", Long.class)).isEqualTo(3);
        client.get().uri("/api/v1/reviews/{id}", aliceReview).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.helpfulVotes").isEqualTo(2).jsonPath("$.unhelpfulVotes").isEqualTo(1);

        // summary: approved = 5 and 4 -> 4.5; total counts the pending one too
        client.get().uri("/api/v1/reviews/product/1/summary").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.productId").isEqualTo(1)
                .jsonPath("$.averageRating").isEqualTo(4.5)
                .jsonPath("$.totalReviews").isEqualTo(3)
                .jsonPath("$.approvedReviews").isEqualTo(2)
                .jsonPath("$.ratingDistribution['5']").isEqualTo(1)
                .jsonPath("$.ratingDistribution['4']").isEqualTo(1)
                .jsonPath("$.ratingDistribution['2']").isEqualTo(0);

        // editing an approved review sends it back to moderation
        client.put().uri("/api/v1/reviews/{id}", bobReview).header("X-Username", "bob")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("rating", 1))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("PENDING").jsonPath("$.rating").isEqualTo(1);
        client.get().uri("/api/v1/reviews/product/1/summary").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.averageRating").isEqualTo(5.0).jsonPath("$.approvedReviews").isEqualTo(1);

        client.get().uri("/api/v1/reviews/customer/alice").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    void ownershipIsEnforcedFromGatewayHeaders() {
        productExists(1L);
        long id = createReview(1L, "alice", 5);

        client.put().uri("/api/v1/reviews/{id}", id).header("X-Username", "mallory")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("rating", 1))
                .exchange().expectStatus().isForbidden();
        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "mallory").header("X-User-Role", "CUSTOMER")
                .exchange().expectStatus().isForbidden();
        client.delete().uri("/api/v1/reviews/{id}", id)
                .exchange().expectStatus().isUnauthorized();

        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "root").header("X-User-Role", "ADMIN")
                .exchange().expectStatus().isNoContent();
        client.get().uri("/api/v1/reviews/{id}", id).exchange().expectStatus().isNotFound();
    }

    @Test
    void deletingAReviewWithVotes_removesTheVotes() {
        productExists(1L);
        long id = createReview(1L, "alice", 5);
        moderate(id, "APPROVED");
        vote(id, "bob", true);
        vote(id, "carol", false);

        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "alice")
                .exchange().expectStatus().isNoContent();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_votes", Long.class)).isZero();
    }

    @Test
    void unknownProduct_isAClientError() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/999").willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":404,\"error\":\"Not Found\",\"message\":\"Product not found with id: 999\"}")));

        client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(999L, 5))
                .exchange()
                .expectStatus().is4xxClientError();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isZero();
    }

    @Test
    void productServiceErroring_rejectsReview_andStoresNothing() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withStatus(503)));

        client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L, 5))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.message").isEqualTo("An unexpected error occurred");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isZero();
    }

    @Test
    void productServiceDroppingConnections_rejectsReview_withoutFeignRetries() {
        // Connection reset after the request was sent is not a connect failure, so
        // FeignConfig.ConnectionFailureRetryer must not repeat it (that would be 3
        // attempts). Apache HttpClient 5's own default retry strategy re-sends an
        // idempotent GET once on a reset, hence 2 requests at the wire.
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L, 5))
                .exchange()
                .expectStatus().is5xxServerError();

        PRODUCT_SERVICE.verify(2, getRequestedFor(urlEqualTo("/api/v1/products/1")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isZero();
    }

    @Test
    void productServiceRecovers_reviewThenSucceeds() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withStatus(503)));
        client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(1L, 5))
                .exchange().expectStatus().is5xxServerError();

        PRODUCT_SERVICE.resetAll();
        productExists(1L);
        createReview(1L, "alice", 5);
    }

    /**
     * ProductServiceClient declares {@code fallback = ProductServiceClientFallback.class}
     * and application.yaml configures a resilience4j instance for product-service, but
     * {@code spring.cloud.openfeign.circuitbreaker.enabled} is never set, so Feign
     * builds a plain client: the fallback is dead code and no circuit ever opens.
     */
    @Test
    void productClient_whenProductServiceFails_goesThroughTheFallback() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/1").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> productServiceClient.getProductById(1L))
                .hasMessage("Product service unavailable - circuit breaker open");
    }

    // ───────────────────────── helpers ─────────────────────────

    private static void productExists(long id) {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/" + id).willReturn(okJson(
                "{\"id\":" + id + ",\"sku\":\"SKU-" + id + "\",\"name\":\"Wireless Mouse\",\"price\":49.99,"
                        + "\"stockQuantity\":50,\"status\":\"ACTIVE\"}")));
    }

    private static Map<String, Object> reviewBody(long productId, int rating) {
        return Map.of("productId", productId, "rating", rating, "title", "Great mouse",
                "body", "Comfortable, precise and the battery lasts for weeks.");
    }

    private long createReview(long productId, String customer, int rating) {
        @SuppressWarnings("unchecked")
        Map<String, Object> created = client.post().uri("/api/v1/reviews").header("X-Username", customer)
                .contentType(MediaType.APPLICATION_JSON).body(reviewBody(productId, rating))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(created).containsEntry("status", "PENDING").containsEntry("customerId", customer);
        return ((Number) created.get("id")).longValue();
    }

    private void moderate(long id, String status) {
        client.patch().uri("/api/v1/reviews/{id}/moderate?status={s}", id, status)
                .header("X-Username", "root").header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo(status);
    }

    private RestTestClient.BodyContentSpec vote(long id, String voter, boolean helpful) {
        return client.post().uri("/api/v1/reviews/{id}/vote", id).header("X-Username", voter)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("helpful", helpful))
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }
}
