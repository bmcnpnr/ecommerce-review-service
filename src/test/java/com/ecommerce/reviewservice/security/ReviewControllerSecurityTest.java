package com.ecommerce.reviewservice.security;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.controller.ReviewController;
import com.ecommerce.reviewservice.dto.ReviewDTO;
import com.ecommerce.reviewservice.model.ReviewStatus;
import com.ecommerce.reviewservice.service.ReviewService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Identity, role and input rules at the HTTP edge of {@link ReviewController}. Identity
 * comes only from the gateway-forwarded {@code X-Username} / {@code X-User-Role}; the service is
 * mocked so "never called" proves a request was stopped before any business logic.
 */
@Tag("security")
@WebMvcTest(controllers = ReviewController.class)
class ReviewControllerSecurityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VALID_REVIEW =
            "{\"productId\":1,\"rating\":5,\"title\":\"Great mouse\",\"body\":\"Comfortable and precise, battery lasts.\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    // the Feign client is registered by @EnableFeignClients even in the slice
    @MockitoBean
    private ProductServiceClient productServiceClient;

    private static ReviewDTO review(long id, String customer, ReviewStatus status) {
        return ReviewDTO.builder().id(id).productId(1L).customerId(customer).rating((short) 5)
                .title("Great mouse").body("Comfortable and precise, battery lasts.").status(status).build();
    }

    private static String createJson(Object rating, String title, String body) {
        return JSON.writeValueAsString(Map.of("productId", 1, "rating", rating, "title", title, "body", body));
    }

    // ───────────────────────── moderation needs ADMIN ─────────────────────────

    @Test
    void moderate_asAdmin_isAllowed() throws Exception {
        when(reviewService.moderateReview(11L, ReviewStatus.APPROVED)).thenReturn(review(11L, "alice", ReviewStatus.APPROVED));

        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "APPROVED")
                        .header("X-Username", "root").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void moderate_withoutRoleHeader_isForbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "APPROVED"))
                .andExpect(status().isForbidden());

        verify(reviewService, never()).moderateReview(anyLong(), any());
    }

    @Test
    void customer_cannotApproveOwnReview() throws Exception {
        // alice is the author of review 11 and claims no admin role
        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "APPROVED")
                        .header("X-Username", "alice").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());

        verify(reviewService, never()).moderateReview(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "", " ADMIN", "ADMIN ", "ROLE_ADMIN", "ADMINISTRATOR", "ADMIN,CUSTOMER", "AD MIN", "null", "0", "true"})
    void moderate_roleThatIsNotExactlyADMIN_isForbidden(String role) throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "APPROVED")
                        .header("X-Username", "alice").header("X-User-Role", role))
                .andExpect(status().isForbidden());

        verify(reviewService, never()).moderateReview(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Admin", "aDmIn"})
    @Disabled("BUG S-30: the role check is case-insensitive (equalsIgnoreCase), so 'admin'/'Admin' moderate reviews "
            + "although the only role the platform issues is exactly 'ADMIN'")
    void moderate_roleWithDifferentCase_isForbidden(String role) throws Exception {
        when(reviewService.moderateReview(anyLong(), any())).thenReturn(review(11L, "alice", ReviewStatus.APPROVED));

        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "APPROVED")
                        .header("X-Username", "alice").header("X-User-Role", role))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED'; DROP TABLE reviews;--", "approved", "1", "<script>", "", "%00", "APPROVED,REJECTED"})
    void moderate_invalidStatus_is400_beforeAnyWork(String status) throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", status)
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isBadRequest());

        verify(reviewService, never()).moderateReview(anyLong(), any());
    }

    // ───────────────────────── identity is the header, nothing else ─────────────────────────

    @Test
    void create_authorIsTheHeader_notAnythingInTheBody() throws Exception {
        when(reviewService.createReview(any(), anyString())).thenReturn(review(11L, "alice", ReviewStatus.PENDING));
        String body = JSON.writeValueAsString(Map.of("productId", 1, "rating", 5, "title", "Great mouse",
                "body", "Comfortable and precise, battery lasts.",
                "customerId", "victim", "customer_id", "victim", "username", "victim", "status", "APPROVED",
                "helpfulVotes", 999, "id", 12345));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        verify(reviewService).createReview(any(), eq("alice"));
        verify(reviewService, never()).createReview(any(), eq("victim"));
    }

    @Test
    @Disabled("BUG S-31: a request without X-Username is not rejected but becomes the shared user 'anonymous', "
            + "who can create a review (one per product, for everyone)")
    void create_withoutIdentity_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(VALID_REVIEW))
                .andExpect(status().is4xxClientError());

        verify(reviewService, never()).createReview(any(), eq("anonymous"));
    }

    @Test
    @Disabled("BUG S-31: a request with a blank X-Username is accepted and stored with an empty author")
    void create_withBlankIdentity_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "  ")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REVIEW))
                .andExpect(status().is4xxClientError());

        verify(reviewService, never()).createReview(any(), eq("  "));
    }

    @Test
    @Disabled("BUG S-31: update/vote/delete without X-Username run as the shared user 'anonymous' instead of being rejected")
    void updateVoteDelete_withoutIdentity_areRejected() throws Exception {
        mockMvc.perform(put("/api/v1/reviews/11").contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/reviews/11/vote").contentType(MediaType.APPLICATION_JSON).content("{\"helpful\":true}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete("/api/v1/reviews/11")).andExpect(status().is4xxClientError());
    }

    // ───────────────────────── validation ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "-1", "100", "32768", "99999999999", "-99999999999", "\"five\"", "null", "true", "[5]", "{}"})
    void create_ratingOutsideOneToFive_isRejected(String rating) throws Exception {
        String body = "{\"productId\":1,\"rating\":" + rating + ",\"title\":\"Great mouse\",\"body\":\"Comfortable and precise.\"}";

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(reviewService, never()).createReview(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "-1", "32768", "99999999999"})
    void update_ratingOutsideOneToFive_isRejected(String rating) throws Exception {
        mockMvc.perform(put("/api/v1/reviews/11").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":" + rating + "}"))
                .andExpect(status().isBadRequest());

        verify(reviewService, never()).updateReview(anyLong(), any(), anyString());
    }

    @Test
    void create_oversizedOrMissingText_isRejected() throws Exception {
        String ok = "Comfortable and precise.";
        for (String body : new String[]{
                createJson(5, "t".repeat(256), ok),
                createJson(5, "Great mouse", "b".repeat(2001)),
                createJson(5, "ab", ok),
                createJson(5, "Great mouse", "short"),
                createJson(5, "   ", ok),
                createJson(5, "Great mouse", "          "),
                "{\"productId\":1,\"rating\":5}"}) {
            mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(reviewService, never()).createReview(any(), anyString());
    }

    @Test
    void update_oversizedText_isRejected() throws Exception {
        for (String body : new String[]{
                "{\"title\":\"" + "t".repeat(256) + "\"}", "{\"body\":\"" + "b".repeat(2001) + "\"}", "{\"title\":\"ab\"}", "{\"body\":\"short\"}"}) {
            mockMvc.perform(put("/api/v1/reviews/11").header("X-Username", "alice")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(reviewService, never()).updateReview(anyLong(), any(), anyString());
    }

    @Test
    void markupAndSqlInText_isPassedThroughVerbatim_asData() throws Exception {
        when(reviewService.createReview(any(), anyString())).thenReturn(review(11L, "alice", ReviewStatus.PENDING));
        String title = "<script>alert(1)</script>";
        String body = "\"><img src=x onerror=alert(1)> ' OR '1'='1'; DROP TABLE reviews;--";

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(5, title, body)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verify(reviewService).createReview(argThatTextIs(title, body), eq("alice"));
    }

    private static com.ecommerce.reviewservice.dto.CreateReviewRequest argThatTextIs(String title, String body) {
        return org.mockito.ArgumentMatchers.argThat(r -> title.equals(r.getTitle()) && body.equals(r.getBody()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1%20OR%201=1", "1.5", "99999999999999999999", "%27", "%00", "-"})
    void nonNumericIds_inPaths_are400_neverReachTheService(String id) throws Exception {
        for (String path : new String[]{"/api/v1/reviews/" + id, "/api/v1/reviews/product/" + id,
                "/api/v1/reviews/product/" + id + "/summary"}) {
            mockMvc.perform(get(path)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(delete("/api/v1/reviews/" + id).header("X-Username", "alice")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/reviews/" + id + "/vote").header("X-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON).content("{\"helpful\":true}")).andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }

    @Test
    void customerIdPathParameter_isBoundAsAParameter_notInterpreted() throws Exception {
        when(reviewService.getReviewsByCustomer(anyString())).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/v1/reviews/customer/{c}", "' OR '1'='1")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mockMvc.perform(get("/api/v1/reviews/customer/{c}", "alice' --")).andExpect(status().isOk());

        verify(reviewService).getReviewsByCustomer("' OR '1'='1");
        verify(reviewService).getReviewsByCustomer("alice' --");
        verify(reviewService, never()).getReviewsByCustomer("alice");
    }

    // ───────────────────────── error bodies ─────────────────────────

    @Test
    void unexpectedFailure_returnsGeneric500_withoutTraceSqlOrClassNames() throws Exception {
        when(reviewService.getReviewById(anyLong())).thenThrow(new IllegalStateException(
                "could not execute statement [ERROR: relation \"reviews\" does not exist] jdbc:postgresql://db:5432/reviewdb"));

        MvcResult result = mockMvc.perform(get("/api/v1/reviews/5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("does not exist").doesNotContain("jdbc").doesNotContain("IllegalStateException")
                .doesNotContain("\tat ").doesNotContain("com.ecommerce");
    }

    @Test
    @Disabled("BUG S-34: 400s for unreadable bodies and type mismatches return the framework's own message, "
            + "exposing Java type names")
    void clientErrors_doNotEchoFrameworkMessages() throws Exception {
        String badPath = mockMvc.perform(get("/api/v1/reviews/abc")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String badBody = mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":{\"a\":1}}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(badPath).doesNotContain("java.lang").doesNotContain("Long");
        assertThat(badBody).doesNotContain("java.lang").doesNotContain("JsonToken").doesNotContain("JSON parse error");
    }
}
