package com.ecommerce.reviewservice.integration;

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

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * product-service entirely unreachable (connection refused): the Feign call goes
 * through FeignConfig.ConnectionFailureRetryer (3 attempts, 100/200 ms backoff)
 * and the review is rejected without being stored, while endpoints that do not
 * need product-service keep working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.cloud.autoconfigure.LifecycleMvcEndpointAutoConfiguration",
        "management.tracing.sampling.probability=0.0",
        "spring.cloud.openfeign.client.config.default.connect-timeout=1000"
})
class ProductServiceDownIT extends PostgresIntegrationTestSupport {

    @DynamicPropertySource
    static void deadProductService(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.product-service.url", () -> "http://localhost:" + freePort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE review_votes, reviews RESTART IDENTITY CASCADE");
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void createReview_isRejected_afterConnectRetries_andNothingIsStored() {
        long start = System.nanoTime();

        client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("productId", 1, "rating", 5, "title", "Great mouse",
                        "body", "Comfortable, precise and the battery lasts for weeks."))
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().jsonPath("$.message").isEqualTo("An unexpected error occurred");

        // two backoffs (100 ms + 200 ms) prove the connection failure was retried
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isZero();
    }

    @Test
    void readsAndVotesDoNotDependOnProductService() {
        jdbc.update("INSERT INTO reviews (product_id, customer_id, rating, title, body, status) "
                + "VALUES (1, 'bob', 4, 'Solid', 'Does what it says on the box.', 'APPROVED')");
        Long id = jdbc.queryForObject("SELECT id FROM reviews WHERE customer_id = 'bob'", Long.class);

        client.get().uri("/api/v1/reviews/product/1/summary").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.averageRating").isEqualTo(4.0);
        client.post().uri("/api/v1/reviews/{id}/vote", id).header("X-Username", "carol")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("helpful", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.helpfulVotes").isEqualTo(1);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
