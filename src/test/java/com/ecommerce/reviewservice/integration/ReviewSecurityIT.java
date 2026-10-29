package com.ecommerce.reviewservice.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review-service over HTTP on PostgreSQL (production Flyway schema), product-service played
 * by WireMock. Identity is whatever the gateway forwards in {@code X-Username} /
 * {@code X-User-Role}; every test here is about what a caller can do with — or without — it.
 */
@Tag("security")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.cloud.autoconfigure.LifecycleMvcEndpointAutoConfiguration",
        "management.tracing.sampling.probability=0.0",
        "spring.cloud.openfeign.client.config.default.read-timeout=3000"
})
class ReviewSecurityIT extends PostgresIntegrationTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final WireMockServer PRODUCT_SERVICE = new WireMockServer(options().dynamicPort());

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

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE review_votes, reviews RESTART IDENTITY CASCADE");
        PRODUCT_SERVICE.resetAll();
        for (long id : new long[]{1, 2}) {
            PRODUCT_SERVICE.stubFor(get("/api/v1/products/" + id).willReturn(okJson(
                    "{\"id\":" + id + ",\"sku\":\"SKU-" + id + "\",\"name\":\"Wireless Mouse\",\"price\":49.99,"
                            + "\"stockQuantity\":50,\"status\":\"ACTIVE\"}")));
        }
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    // ───────────────────────── helpers ─────────────────────────

    private static Map<String, Object> reviewBody(long productId, int rating) {
        return new LinkedHashMap<>(Map.of("productId", productId, "rating", rating, "title", "Great mouse",
                "body", "Comfortable, precise and the battery lasts for weeks."));
    }

    private long createReview(long productId, String customer) {
        String body = client.post().uri("/api/v1/reviews").header("X-Username", customer)
                .contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(reviewBody(productId, 5)))
                .exchange().expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return JSON.readTree(body).get("id").asLong();
    }

    private RestTestClient.ResponseSpec post(Map<String, Object> body, String user) {
        var req = client.post().uri("/api/v1/reviews");
        if (user != null) req = req.header("X-Username", user);
        return req.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body)).exchange();
    }

    private void moderate(long id, String status, String role) {
        client.patch().uri("/api/v1/reviews/{id}/moderate?status={s}", id, status)
                .header("X-Username", "root").header("X-User-Role", role)
                .exchange().expectStatus().isOk();
    }

    private String dbString(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    // ───────────────────────── authorship ─────────────────────────

    @Test
    void editingSomeoneElsesReview_is403_andTheRowIsUnchanged() {
        long id = createReview(1L, "alice");

        client.put().uri("/api/v1/reviews/{id}", id).header("X-Username", "mallory")
                .contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"Hijacked title\",\"rating\":1}")
                .exchange().expectStatus().isForbidden();

        assertThat(dbString("SELECT title FROM reviews WHERE id = ?", id)).isEqualTo("Great mouse");
        assertThat(jdbc.queryForObject("SELECT rating FROM reviews WHERE id = ?", Integer.class, id)).isEqualTo(5);
    }

    @Test
    void unauthenticatedCaller_cannotEditDeleteOrModerateSomeoneElsesReview() {
        long id = createReview(1L, "alice");

        client.put().uri("/api/v1/reviews/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Defaced title\"}").exchange().expectStatus().isForbidden();
        client.delete().uri("/api/v1/reviews/{id}", id).exchange().expectStatus().isForbidden();
        client.patch().uri("/api/v1/reviews/{id}/moderate?status=APPROVED", id).exchange().expectStatus().isForbidden();

        assertThat(count("reviews")).isEqualTo(1);
        assertThat(dbString("SELECT title FROM reviews WHERE id = ?", id)).isEqualTo("Great mouse");
        assertThat(dbString("SELECT status FROM reviews WHERE id = ?", id)).isEqualTo("PENDING");
    }

    @Test
    void deletingSomeoneElsesReview_asCustomer_is403_asAdmin_isAllowed() {
        long id = createReview(1L, "alice");

        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "mallory").header("X-User-Role", "CUSTOMER")
                .exchange().expectStatus().isForbidden();
        assertThat(count("reviews")).isEqualTo(1);

        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "root").header("X-User-Role", "ADMIN")
                .exchange().expectStatus().isNoContent();
        assertThat(count("reviews")).isZero();
    }

    @Test
    void author_mayDeleteOwnReview() {
        long id = createReview(1L, "alice");

        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "alice")
                .exchange().expectStatus().isNoContent();

        assertThat(count("reviews")).isZero();
    }

    @Test
    @Disabled("BUG S-30: the role check is case-insensitive, so a request with X-User-Role 'admin' deletes and moderates "
            + "other users' reviews")
    void roleWithWrongCase_isNotAnAdmin() {
        long id = createReview(1L, "alice");

        client.patch().uri("/api/v1/reviews/{id}/moderate?status=APPROVED", id)
                .header("X-Username", "mallory").header("X-User-Role", "admin")
                .exchange().expectStatus().isForbidden();
        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "mallory").header("X-User-Role", "Admin")
                .exchange().expectStatus().isForbidden();
        assertThat(count("reviews")).isEqualTo(1);
    }

    // ───────────────────────── moderation & self-approval ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "", "ROLE_ADMIN", "ADMINX", "0"})
    void customer_cannotApproveOwnReview_whateverTheRoleHeaderSaysExceptADMIN(String role) {
        long id = createReview(1L, "alice");

        client.patch().uri("/api/v1/reviews/{id}/moderate?status=APPROVED", id)
                .header("X-Username", "alice").header("X-User-Role", role)
                .exchange().expectStatus().isForbidden();

        assertThat(dbString("SELECT status FROM reviews WHERE id = ?", id)).isEqualTo("PENDING");
        client.get().uri("/api/v1/reviews/product/1").exchange().expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void admin_canModerate_andTheReviewBecomesPublic() {
        long id = createReview(1L, "alice");

        moderate(id, "APPROVED", "ADMIN");

        assertThat(dbString("SELECT status FROM reviews WHERE id = ?", id)).isEqualTo("APPROVED");
        client.get().uri("/api/v1/reviews/product/1").exchange().expectBody().jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    void invalidModerationStatus_is400_andNothingChanges() {
        long id = createReview(1L, "alice");

        for (String status : new String[]{"approved", "DELETED", "APPROVED';DROP TABLE reviews;--", "1"}) {
            client.patch().uri("/api/v1/reviews/{id}/moderate?status={s}", id, status)
                    .header("X-User-Role", "ADMIN").exchange().expectStatus().isBadRequest();
        }
        assertThat(dbString("SELECT status FROM reviews WHERE id = ?", id)).isEqualTo("PENDING");
    }

    // ───────────────────────── mass assignment ─────────────────────────

    @Test
    void create_ignoresStatusAuthorVotesIdAndTimestampsFromTheBody() {
        Map<String, Object> body = reviewBody(1L, 5);
        body.put("status", "APPROVED");
        body.put("customerId", "victim");
        body.put("customer_id", "victim");
        body.put("helpfulVotes", 999);
        body.put("helpfulCount", 999);
        body.put("id", 12345);
        body.put("createdAt", "1999-01-01T00:00:00");
        body.put("votes", java.util.List.of(Map.of("customerId", "x", "helpful", true)));

        String response = post(body, "mallory").expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();

        JsonNode created = JSON.readTree(response);
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("customerId").asString()).isEqualTo("mallory");
        assertThat(created.get("id").asLong()).isNotEqualTo(12345L);
        assertThat(created.get("helpfulVotes").asLong()).isZero();
        assertThat(dbString("SELECT status FROM reviews")).isEqualTo("PENDING");
        assertThat(dbString("SELECT customer_id FROM reviews")).isEqualTo("mallory");
        assertThat(count("review_votes")).isZero();
        assertThat(jdbc.queryForObject("SELECT extract(year FROM created_at)::int FROM reviews", Integer.class)).isGreaterThan(2000);
        client.get().uri("/api/v1/reviews/product/1").exchange().expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void update_ignoresStatusAuthorAndProduct_andResetsApprovalToPending() {
        long id = createReview(1L, "alice");
        moderate(id, "APPROVED", "ADMIN");

        client.put().uri("/api/v1/reviews/{id}", id).header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Edited title\",\"status\":\"APPROVED\",\"customerId\":\"mallory\",\"productId\":2,\"helpfulVotes\":50}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("PENDING").jsonPath("$.customerId").isEqualTo("alice");

        assertThat(dbString("SELECT status FROM reviews WHERE id = ?", id)).isEqualTo("PENDING");
        assertThat(dbString("SELECT customer_id FROM reviews WHERE id = ?", id)).isEqualTo("alice");
        assertThat(jdbc.queryForObject("SELECT product_id FROM reviews WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(dbString("SELECT title FROM reviews WHERE id = ?", id)).isEqualTo("Edited title");
        assertThat(count("review_votes")).isZero();
    }

    // ───────────────────────── anonymous identity ─────────────────────────

    @Test
    @Disabled("BUG S-31: a request without X-Username is accepted as the shared user 'anonymous': it stores a review "
            + "that any other unauthenticated caller can then edit or delete, and burns the single per-product slot")
    void createWithoutIdentity_isRejected_andStoresNothing() {
        post(reviewBody(1L, 5), null).expectStatus().is4xxClientError();

        assertThat(count("reviews")).isZero();
    }

    @Test
    @Disabled("BUG S-31: a blank X-Username is accepted and stored as an empty author")
    void createWithBlankIdentity_isRejected_andStoresNothing() {
        client.post().uri("/api/v1/reviews").header("X-Username", " ")
                .contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(reviewBody(1L, 5)))
                .exchange().expectStatus().is4xxClientError();

        assertThat(count("reviews")).isZero();
    }

    @Test
    @Disabled("BUG S-31: voting without X-Username is accepted as the shared user 'anonymous'")
    void voteWithoutIdentity_isRejected() {
        long id = createReview(1L, "alice");

        client.post().uri("/api/v1/reviews/{id}/vote", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"helpful\":true}").exchange().expectStatus().is4xxClientError();

        assertThat(count("review_votes")).isZero();
    }

    @Test
    void anonymousReviewSlot_cannotBeUsedToTouchARealUsersReview() {
        long id = createReview(1L, "alice");

        // the shared "anonymous" identity gains no rights over alice's data
        client.put().uri("/api/v1/reviews/{id}", id).header("X-Username", "anonymous")
                .contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"Defaced title\"}")
                .exchange().expectStatus().isForbidden();
        client.delete().uri("/api/v1/reviews/{id}", id).header("X-Username", "anonymous")
                .exchange().expectStatus().isForbidden();
        assertThat(dbString("SELECT title FROM reviews WHERE id = ?", id)).isEqualTo("Great mouse");
    }

    // ───────────────────────── votes ─────────────────────────

    @Test
    void manyVotesBySameUser_countOnce() {
        long id = createReview(1L, "alice");
        moderate(id, "APPROVED", "ADMIN");

        for (int i = 0; i < 15; i++) {
            client.post().uri("/api/v1/reviews/{id}/vote", id).header("X-Username", "bob")
                    .contentType(MediaType.APPLICATION_JSON).body("{\"helpful\":true}")
                    .exchange().expectStatus().isOk();
        }

        assertThat(count("review_votes")).isEqualTo(1);
        client.get().uri("/api/v1/reviews/{id}", id).exchange()
                .expectBody().jsonPath("$.helpfulVotes").isEqualTo(1).jsonPath("$.unhelpfulVotes").isEqualTo(0);
    }

    @Test
    void voteWithoutHelpfulFlag_orWithGarbage_is400_andStoresNothing() {
        long id = createReview(1L, "alice");

        for (String body : new String[]{"{}", "{\"helpful\":null}", "{\"helpful\":\"maybe\"}", "{\"helpful\":[true]}", "[]", ""}) {
            client.post().uri("/api/v1/reviews/{id}/vote", id).header("X-Username", "bob")
                    .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isBadRequest();
        }
        assertThat(count("review_votes")).isZero();
    }

    @Test
    @Disabled("BUG S-32: an author can vote their own review 'helpful'")
    void authorCannotVoteOwnReview() {
        long id = createReview(1L, "alice");
        moderate(id, "APPROVED", "ADMIN");

        client.post().uri("/api/v1/reviews/{id}/vote", id).header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body("{\"helpful\":true}")
                .exchange().expectStatus().is4xxClientError();

        assertThat(count("review_votes")).isZero();
    }

    @Test
    @Disabled("BUG S-32: votes are accepted on PENDING (unmoderated, unpublished) reviews")
    void cannotVoteOnAReviewThatIsNotPublished() {
        long id = createReview(1L, "alice");

        client.post().uri("/api/v1/reviews/{id}/vote", id).header("X-Username", "bob")
                .contentType(MediaType.APPLICATION_JSON).body("{\"helpful\":true}")
                .exchange().expectStatus().is4xxClientError();

        assertThat(count("review_votes")).isZero();
    }

    // ───────────────────────── unmoderated content visibility ─────────────────────────

    @Test
    @Disabled("BUG S-33: GET /reviews/{id} returns PENDING/REJECTED reviews (with the author's username) to anyone")
    void unapprovedReview_isNotReadableByOtherUsers() {
        long id = createReview(1L, "alice");

        client.get().uri("/api/v1/reviews/{id}", id).header("X-Username", "bob")
                .exchange().expectStatus().is4xxClientError();
    }

    @Test
    @Disabled("BUG S-33: GET /reviews/product/{id}?approvedOnly=false lists every pending/rejected review to any caller")
    void approvedOnlyFalse_isAnAdminOnlyView() {
        createReview(1L, "alice");

        var result = client.get().uri("/api/v1/reviews/product/1?approvedOnly=false").header("X-Username", "bob")
                .header("X-User-Role", "CUSTOMER").exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().is4xxClientError() || JSON.readTree(result.getResponseBody()).size() == 0)
                .as("a customer sees %s", result.getResponseBody()).isTrue();
    }

    @Test
    @Disabled("BUG S-33: GET /reviews/customer/{customerId} returns another user's pending reviews to any caller")
    void otherUsersPendingReviews_areNotListedByCustomer() {
        createReview(1L, "alice");

        var result = client.get().uri("/api/v1/reviews/customer/alice").header("X-Username", "bob")
                .header("X-User-Role", "CUSTOMER").exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().is4xxClientError() || JSON.readTree(result.getResponseBody()).size() == 0)
                .as("bob sees %s", result.getResponseBody()).isTrue();
    }

    // ───────────────────────── stored XSS / injection ─────────────────────────

    private static final String[] XSS = {
            "<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", "<svg/onload=alert(1)>",
            "javascript:alert(1)", "</textarea><script>alert(document.cookie)</script>", "{{7*7}} ${7*7} #{7*7}"};

    @Test
    void markup_isStoredAndReturnedAsData_inJson_neverAsHtml() {
        long productId = 1;
        int i = 0;
        for (String payload : XSS) {
            String user = "xss_user_" + i++;
            Map<String, Object> body = reviewBody(productId, 4);
            body.put("title", "T " + payload);
            body.put("body", "Body text " + payload + " end");

            var created = post(body, user).expectStatus().isCreated()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .expectBody(String.class).returnResult();
            JsonNode json = JSON.readTree(created.getResponseBody());
            long id = json.get("id").asLong();

            // returned exactly as sent (JSON-escaped on the wire), stored exactly as sent
            assertThat(json.get("title").asString()).isEqualTo("T " + payload);
            assertThat(json.get("body").asString()).isEqualTo("Body text " + payload + " end");
            assertThat(dbString("SELECT title FROM reviews WHERE id = ?", id)).isEqualTo("T " + payload);

            moderate(id, "APPROVED", "ADMIN");
            for (String uri : new String[]{"/api/v1/reviews/" + id, "/api/v1/reviews/product/" + productId,
                    "/api/v1/reviews/customer/" + user}) {
                // a browser asking for HTML is refused (406 with the framework's static error page): the stored
                // markup is never reflected into anything served as text/html
                var r = client.get().uri(uri).header("Accept", "text/html").exchange().expectBody(String.class).returnResult();
                if (String.valueOf(r.getResponseHeaders().getContentType()).contains("html")) {
                    assertThat(r.getStatus().value()).as(uri).isEqualTo(406);
                }
                assertThat(r.getResponseBody()).as(uri).doesNotContain("alert(").doesNotContain("onerror").doesNotContain("onload");
                var j = client.get().uri(uri).exchange().expectStatus().isOk()
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                        .expectBody(String.class).returnResult();
                assertThat(JSON.readTree(j.getResponseBody())).as(uri + " is valid JSON").isNotNull();
            }
        }
    }

    @Test
    @Disabled("BUG S-36: responses carry no X-Content-Type-Options: nosniff, so a browser may MIME-sniff stored markup "
            + "in a JSON response (no Spring Security / security-headers filter in this service)")
    void responses_carryNosniff() {
        long id = createReview(1L, "alice");

        client.get().uri("/api/v1/reviews/{id}", id).exchange()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    @Test
    void sqlMetacharacters_inReviewText_areStoredLiterally_andTheTablesSurvive() {
        String title = "x'); DROP TABLE reviews;--";
        String text = "' OR '1'='1' UNION SELECT customer_id FROM reviews --  and more text";
        Map<String, Object> body = reviewBody(1L, 3);
        body.put("title", title);
        body.put("body", text);

        post(body, "sqli_user").expectStatus().isCreated();

        assertThat(count("reviews")).isEqualTo(1);
        assertThat(dbString("SELECT title FROM reviews")).isEqualTo(title);
        assertThat(dbString("SELECT body FROM reviews")).isEqualTo(text);
    }

    @Test
    void sqlMetacharacters_inTheUsernameHeader_areStoredLiterally_andNeverMatchOtherUsers() {
        createReview(1L, "alice");
        String evil = "alice' OR '1'='1";

        post(reviewBody(1L, 2), evil).expectStatus().isCreated();   // not "already reviewed" by alice

        assertThat(count("reviews")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews WHERE customer_id = ?", Long.class, evil)).isEqualTo(1);
        client.get().uri("/api/v1/reviews/customer/{c}", evil).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].customerId").isEqualTo(evil);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%27%20OR%20%271%27%3D%271", "alice%27--", "%25", "..%2F..%2Fetc%2Fpasswd", "%00", "alice%3BDROP%20TABLE%20reviews"})
    void customerPathParameter_withInjectionOrTraversal_returnsNoOtherUsersData_andNever500(String encoded) {
        createReview(1L, "alice");

        var result = client.get().uri("/api/v1/reviews/customer/" + encoded).exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().is5xxServerError()).as("status %s: %s", result.getStatus(), result.getResponseBody()).isFalse();
        assertThat(result.getResponseBody()).doesNotContain("alice");
        assertThat(count("reviews")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1%20OR%201=1", "1;DROP", "-", "99999999999999999999", "%27", "1.5", "%00", "..%2F1"})
    void numericPathParameters_withInjectionOrTraversal_are4xx_never500(String id) {
        createReview(1L, "alice");

        for (String uri : new String[]{"/api/v1/reviews/" + id, "/api/v1/reviews/product/" + id, "/api/v1/reviews/product/" + id + "/summary"}) {
            var result = client.get().uri(uri).exchange().expectBody(String.class).returnResult();
            if (id.equals("1;DROP")) {
                // a path parameter ending in ';...' is a matrix variable: it is the number 1, treated as data
                assertThat(result.getStatus().is5xxServerError()).as(uri).isFalse();
            } else {
                assertThat(result.getStatus().is4xxClientError()).as("%s -> %s", uri, result.getStatus()).isTrue();
            }
            assertThat(result.getResponseBody()).as(uri).doesNotContain("\tat ").doesNotContain("org.hibernate");
        }
        assertThat(count("reviews")).isEqualTo(1);
    }

    // ───────────────────────── input bounds ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "-1", "100", "32768", "99999999999", "-99999999999", "\"five\"", "null", "true"})
    void ratingOutsideOneToFive_isRejected_andNothingIsStored(String rating) {
        String body = "{\"productId\":1,\"rating\":" + rating + ",\"title\":\"Great mouse\",\"body\":\"Comfortable and precise.\"}";

        client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isBadRequest();

        assertThat(count("reviews")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "-1", "32768", "99999999999"})
    void updateRatingOutsideOneToFive_isRejected_andTheRowIsUnchanged(String rating) {
        long id = createReview(1L, "alice");

        client.put().uri("/api/v1/reviews/{id}", id).header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body("{\"rating\":" + rating + "}")
                .exchange().expectStatus().isBadRequest();

        assertThat(jdbc.queryForObject("SELECT rating FROM reviews WHERE id = ?", Integer.class, id)).isEqualTo(5);
    }

    @Test
    void oversizedText_isRejected_withoutReachingTheDatabase() {
        Map<String, Object> longTitle = reviewBody(1L, 5);
        longTitle.put("title", "t".repeat(256));
        Map<String, Object> longBody = reviewBody(1L, 5);
        longBody.put("body", "b".repeat(2001));
        Map<String, Object> hugeBody = reviewBody(1L, 5);
        hugeBody.put("body", "b".repeat(1_000_000));

        post(longTitle, "alice").expectStatus().isBadRequest();
        post(longBody, "alice").expectStatus().isBadRequest();
        post(hugeBody, "alice").expectStatus().isBadRequest();

        assertThat(count("reviews")).isZero();
    }

    @Test
    @Disabled("BUG S-37: an X-Username longer than the 255-character customer_id column is not validated and ends in a "
            + "database error answered as 500")
    void veryLongUsernameHeader_isA4xx_not500() {
        // customer_id is a plain varchar(255) column in the migrations
        var result = post(reviewBody(1L, 5), "u".repeat(5000)).expectBody(String.class).returnResult();

        assertThat(result.getStatus().is5xxServerError()).as("status %s", result.getStatus()).isFalse();
    }

    // ───────────────────────── error bodies ─────────────────────────

    @Test
    void errorResponses_leakNoStackTraceSqlOrClassNames() {
        long id = createReview(1L, "alice");
        String[] bodies = {
                client.get().uri("/api/v1/reviews/{id}", 987654321L).exchange().expectStatus().isNotFound()
                        .expectBody(String.class).returnResult().getResponseBody(),
                client.put().uri("/api/v1/reviews/{id}", id).header("X-Username", "mallory")
                        .contentType(MediaType.APPLICATION_JSON).body("{\"rating\":1}")
                        .exchange().expectStatus().isForbidden().expectBody(String.class).returnResult().getResponseBody(),
                client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).body("{}")
                        .exchange().expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody(),
                post(reviewBody(1L, 4), "alice").expectStatus().isEqualTo(409).expectBody(String.class).returnResult().getResponseBody(),
                client.patch().uri("/api/v1/reviews/{id}", id).exchange().expectStatus().isEqualTo(405)
                        .expectBody(String.class).returnResult().getResponseBody()};

        for (String body : bodies) {
            assertThat(body).doesNotContain("Exception").doesNotContain("org.springframework").doesNotContain("org.hibernate")
                    .doesNotContain("com.ecommerce").doesNotContain("jdbc:").doesNotContain("postgres").doesNotContain("SELECT ")
                    .doesNotContain("\tat ").doesNotContain("\\tat ").doesNotContain("stackTrace").doesNotContain("\"trace\"");
            JsonNode json = JSON.readTree(body);
            json.propertyNames().forEach(name -> assertThat(name)
                    .isIn("timestamp", "status", "error", "message", "path", "correlationId", "fieldErrors"));
        }
    }

    @Test
    void productServiceFailure_isAGeneric500_withoutUpstreamDetails() {
        PRODUCT_SERVICE.stubFor(get("/api/v1/products/2").willReturn(aResponse().withStatus(500)
                .withBody("java.lang.NullPointerException at com.ecommerce.product.SecretClass jdbc:postgresql://productdb")));

        var result = post(reviewBody(2L, 5), "alice").expectBody(String.class).returnResult();

        assertThat(result.getStatus().isError()).isTrue();
        assertThat(result.getResponseBody()).doesNotContain("NullPointer").doesNotContain("SecretClass").doesNotContain("productdb");
        assertThat(count("reviews")).isZero();
    }

    @Test
    @Disabled("BUG S-34: unreadable bodies and non-numeric ids return the framework's own message, exposing Java type names")
    void clientErrors_doNotEchoFrameworkMessages() {
        String badPath = client.get().uri("/api/v1/reviews/abc").exchange().expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody();
        String badBody = client.post().uri("/api/v1/reviews").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).body("{\"productId\":{\"a\":1}}")
                .exchange().expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();

        assertThat(badPath).doesNotContain("java.lang").doesNotContain("Long");
        assertThat(badBody).doesNotContain("java.lang").doesNotContain("JsonToken").doesNotContain("JSON parse error");
    }

    @Test
    @Disabled("BUG S-35: a request for a URL that does not exist is answered 500 'An unexpected error occurred' "
            + "(NoResourceFoundException falls into the catch-all handler) instead of 404")
    void unknownPath_is404_not500() {
        client.get().uri("/api/v1/nope").exchange().expectStatus().isNotFound();
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
    }

    @Test
    void sensitiveActuatorEndpoints_areNotExposed() {
        for (String path : new String[]{"/actuator/env", "/actuator/heapdump", "/actuator/beans", "/actuator/configprops",
                "/actuator/loggers", "/actuator/threaddump", "/actuator/mappings"}) {
            var result = client.get().uri(path).exchange().expectBody(String.class).returnResult();
            assertThat(result.getStatus().value()).as(path).isNotEqualTo(200);
            assertThat(result.getResponseBody()).as(path).doesNotContain("propertySources").doesNotContain("activeProfiles");
        }
    }
}
