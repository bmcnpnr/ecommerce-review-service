package com.ecommerce.reviewservice.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.dto.ProductDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Provider side of the HTTP contract api-gateway holds with review-service.
 *
 * <p>The gateway validates the caller's JWT and forwards the request with
 * {@code X-Username}, {@code X-User-Id} and {@code X-User-Role}; review-service takes
 * the review author and the admin override from those headers. The pact (copy in
 * {@code src/test/resources/pacts/}, see {@code ecommerce-platform/sync-pacts.sh}) is
 * replayed here against the real application — controller, validation, service, JPA on
 * H2 — on a random port. product-service, which {@code createReview} consults through
 * Feign, is the one collaborator that is mocked (its contract is verified separately by
 * {@code ProductServiceConsumerPactTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Provider("review-service")
@PactFolder("pacts")
class ReviewServiceProviderPactTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ProductServiceClient productServiceClient;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ───────────────────────── provider states ─────────────────────────
    // State names are part of the contract: consumers reference them verbatim.

    @State("product 1 exists and alice has not reviewed it")
    void product1ExistsUnreviewedByAlice() {
        clearReviews();
        ProductDTO product = new ProductDTO();
        product.setId(1L);
        product.setSku("SKU-001");
        product.setName("Wireless Mouse");
        product.setPrice(new BigDecimal("49.99"));
        product.setStockQuantity(50);
        product.setStatus("ACTIVE");
        when(productServiceClient.getProductById(eq(1L))).thenReturn(product);
    }

    @State("review 11 by bob exists")
    void review11ByBobExists() {
        clearReviews();
        jdbc.update("INSERT INTO reviews (id, product_id, customer_id, rating, title, body, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                11L, 1L, "bob", (short) 4, "Solid", "Does what it says on the box, no complaints so far.",
                "APPROVED", LocalDateTime.now(), LocalDateTime.now());
    }

    private void clearReviews() {
        jdbc.update("DELETE FROM review_votes");
        jdbc.update("DELETE FROM reviews");
    }
}
