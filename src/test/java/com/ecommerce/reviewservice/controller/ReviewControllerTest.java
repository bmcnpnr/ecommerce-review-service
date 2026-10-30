package com.ecommerce.reviewservice.controller;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.dto.*;
import com.ecommerce.reviewservice.exception.DuplicateReviewException;
import com.ecommerce.reviewservice.exception.ReviewNotFoundException;
import com.ecommerce.reviewservice.exception.UnauthorizedReviewAccessException;
import com.ecommerce.reviewservice.model.ReviewStatus;
import com.ecommerce.reviewservice.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Every endpoint of {@link ReviewController}, incl. how the gateway-supplied
 * identity headers ({@code X-Username}, {@code X-User-Role}) reach the service.
 */
@WebMvcTest(controllers = ReviewController.class)
class ReviewControllerTest {

    private static final String VALID_REVIEW =
            "{\"productId\":1,\"rating\":5,\"title\":\"Great mouse\",\"body\":\"Comfortable and precise, battery lasts.\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    // @EnableFeignClients on the application class registers the Feign client even in
    // this slice; replace it so the slice does not need the Feign infrastructure.
    @MockitoBean
    private ProductServiceClient productServiceClient;

    private static ReviewDTO review(long id, String customer, ReviewStatus status) {
        return ReviewDTO.builder().id(id).productId(1L).customerId(customer).rating((short) 5)
                .title("Great mouse").body("Comfortable and precise, battery lasts.").status(status).build();
    }

    // ───────────────────────── create ─────────────────────────

    @Test
    void create_returns201_andUsesXUsernameAsAuthor() throws Exception {
        when(reviewService.createReview(any(), eq("alice"))).thenReturn(review(11L, "alice", ReviewStatus.PENDING));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REVIEW))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.customerId").value("alice"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(reviewService).createReview(argThat(r -> r.getProductId() == 1L && r.getRating() == 5
                && r.getTitle().equals("Great mouse")), eq("alice"));
    }

    @Test
    void create_withoutXUsername_isRejectedWith401() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(VALID_REVIEW))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));

        verifyNoInteractions(reviewService);
    }

    @Test
    void create_invalidBody_returns400_withFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":6,\"title\":\"ab\",\"body\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(4)))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='productId')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='rating')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='title')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='body')]").exists());

        verifyNoInteractions(reviewService);
    }

    @Test
    void create_ratingBelowOne_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REVIEW.replace("\"rating\":5", "\"rating\":0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("rating"));
    }

    @Test
    void create_duplicate_returns409() throws Exception {
        when(reviewService.createReview(any(), eq("alice")))
                .thenThrow(new DuplicateReviewException("Customer alice has already reviewed product 1"));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REVIEW))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Customer alice has already reviewed product 1"));
    }

    // ───────────────────────── reads ─────────────────────────

    @Test
    void getById_returns200() throws Exception {
        when(reviewService.getReviewById(11L)).thenReturn(review(11L, "alice", ReviewStatus.APPROVED));

        mockMvc.perform(get("/api/v1/reviews/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(reviewService.getReviewById(99L)).thenThrow(new ReviewNotFoundException("Review not found with id: 99"));

        mockMvc.perform(get("/api/v1/reviews/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Review not found with id: 99"))
                .andExpect(jsonPath("$.path").value("/api/v1/reviews/99"));
    }

    @Test
    void aRequestBodyOver1MB_isRefusedBeforeAnythingParsesIt() throws Exception {
        byte[] huge = ("{\"productId\":1,\"rating\":5,\"title\":\"t\",\"body\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Request body too large"));

        verifyNoInteractions(reviewService);
    }

    @Test
    void getByProduct_defaultsToApprovedOnly() throws Exception {
        when(reviewService.getReviewsByProduct(eq(1L), eq(true), any(Pageable.class)))
                .thenReturn(List.of(review(11L, "alice", ReviewStatus.APPROVED)));

        mockMvc.perform(get("/api/v1/reviews/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // no page parameters: the first page of 100, oldest first
        verify(reviewService).getReviewsByProduct(eq(1L), eq(true), argThat((Pageable p) ->
                p.getPageNumber() == 0 && p.getPageSize() == 100 && p.getSort().equals(Sort.by("id"))));
    }

    @Test
    void getByProduct_pageAndSizeAreForwarded() throws Exception {
        when(reviewService.getReviewsByProduct(eq(1L), eq(true), any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/reviews/product/1").param("page", "3").param("size", "25")).andExpect(status().isOk());

        verify(reviewService).getReviewsByProduct(eq(1L), eq(true), argThat((Pageable p) -> p.getPageNumber() == 3 && p.getPageSize() == 25));
    }

    @Test
    void listEndpoints_rejectPagesThatAreTooBigOrOutOfRange_withoutTouchingTheService() throws Exception {
        for (String path : List.of("/api/v1/reviews/product/1", "/api/v1/reviews/customer/alice")) {
            for (String query : List.of("size=101", "size=0", "size=-1", "page=-1", "size=2147483647",
                    "page=2147483647&size=100", "size=abc", "page=1.5")) {
                mockMvc.perform(get(path + "?" + query).header("X-Username", "alice"))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(reviewService);
    }

    @Test
    void getByProduct_approvedOnlyFalse_includesAllStatuses() throws Exception {
        when(reviewService.getReviewsByProduct(eq(1L), eq(false), any(Pageable.class))).thenReturn(List.of(
                review(11L, "alice", ReviewStatus.APPROVED), review(12L, "bob", ReviewStatus.PENDING)));

        mockMvc.perform(get("/api/v1/reviews/product/1").param("approvedOnly", "false")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getByProduct_nonBooleanFlag_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/product/1").param("approvedOnly", "maybe"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSummary_returns200() throws Exception {
        when(reviewService.getProductReviewSummary(1L)).thenReturn(ReviewSummaryDTO.builder()
                .productId(1L).averageRating(4.3).totalReviews(4).approvedReviews(3)
                .ratingDistribution(Map.of(1, 0L, 2, 0L, 3, 0L, 4, 2L, 5, 1L)).build());

        mockMvc.perform(get("/api/v1/reviews/product/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.3))
                .andExpect(jsonPath("$.totalReviews").value(4))
                .andExpect(jsonPath("$.approvedReviews").value(3))
                .andExpect(jsonPath("$.ratingDistribution['4']").value(2));
    }

    @Test
    void getByCustomer_returns200() throws Exception {
        when(reviewService.getReviewsByCustomer(eq("alice"), eq(false), any(Pageable.class)))
                .thenReturn(List.of(review(11L, "alice", ReviewStatus.PENDING)));

        mockMvc.perform(get("/api/v1/reviews/customer/alice").header("X-Username", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerId").value("alice"));
    }

    @Test
    void getByCustomer_unpublishedReviewsAreForTheirAuthorAndAdminsOnly() throws Exception {
        when(reviewService.getReviewsByCustomer(anyString(), anyBoolean(), any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/reviews/customer/alice").header("X-Username", "alice"));       // the author
        mockMvc.perform(get("/api/v1/reviews/customer/alice").header("X-Username", "root").header("X-User-Role", "ADMIN"));
        mockMvc.perform(get("/api/v1/reviews/customer/alice").header("X-Username", "mallory"));     // somebody else
        mockMvc.perform(get("/api/v1/reviews/customer/alice").header("X-Username", "root").header("X-User-Role", "admin")); // not ADMIN
        mockMvc.perform(get("/api/v1/reviews/customer/alice"));                                     // anonymous

        // approvedOnly is decided here and applied by the database, so a page is never filtered after the fact
        verify(reviewService, times(2)).getReviewsByCustomer(eq("alice"), eq(false), any(Pageable.class));
        verify(reviewService, times(3)).getReviewsByCustomer(eq("alice"), eq(true), any(Pageable.class));
    }

    // ───────────────────────── update / delete ─────────────────────────

    @Test
    void update_passesXUsernameForOwnershipCheck() throws Exception {
        when(reviewService.updateReview(eq(11L), any(), eq("alice"))).thenReturn(review(11L, "alice", ReviewStatus.PENDING));

        mockMvc.perform(put("/api/v1/reviews/11").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":3}"))
                .andExpect(status().isOk());

        verify(reviewService).updateReview(eq(11L), argThat(r -> r.getRating() == 3 && r.getTitle() == null), eq("alice"));
    }

    @Test
    void update_byNonOwner_returns403() throws Exception {
        when(reviewService.updateReview(eq(11L), any(), eq("mallory")))
                .thenThrow(new UnauthorizedReviewAccessException("Only the review author can update this review"));

        mockMvc.perform(put("/api/v1/reviews/11").header("X-Username", "mallory")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void update_withoutXUsername_isRejectedWith401() throws Exception {
        mockMvc.perform(put("/api/v1/reviews/11").contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    void update_invalidFields_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/reviews/11").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":9,\"title\":\"x\",\"body\":\"tiny\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasSize(3)));
        verifyNoInteractions(reviewService);
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(reviewService.updateReview(eq(99L), any(), any())).thenThrow(new ReviewNotFoundException("Review not found with id: 99"));

        mockMvc.perform(put("/api/v1/reviews/99").header("X-Username", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":2}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_passesUsernameAndRole_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/11").header("X-Username", "root").header("X-User-Role", "ADMIN"))
                .andExpect(status().isNoContent());

        verify(reviewService).deleteReview(11L, "root", "ADMIN");
    }

    @Test
    void delete_withoutHeaders_isRejectedWith401() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/11"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    void delete_byNonOwner_returns403() throws Exception {
        doThrow(new UnauthorizedReviewAccessException("Only the review author or an admin can delete this review"))
                .when(reviewService).deleteReview(11L, "mallory", "CUSTOMER");

        mockMvc.perform(delete("/api/v1/reviews/11").header("X-Username", "mallory").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        doThrow(new ReviewNotFoundException("Review not found with id: 99")).when(reviewService).deleteReview(eq(99L), any(), any());

        mockMvc.perform(delete("/api/v1/reviews/99").header("X-Username", "alice"))
                .andExpect(status().isNotFound());
    }

    // ───────────────────────── moderation ─────────────────────────

    @Test
    void moderate_setsStatus() throws Exception {
        when(reviewService.moderateReview(11L, ReviewStatus.APPROVED)).thenReturn(review(11L, "alice", ReviewStatus.APPROVED));

        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "APPROVED")
                        .header("X-Username", "root").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void moderate_unknownStatus_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "PUBLISHED"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reviewService);
    }

    @Test
    void moderate_missingStatus_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/reviews/11/moderate"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moderate_notFound_returns404() throws Exception {
        when(reviewService.moderateReview(99L, ReviewStatus.REJECTED)).thenThrow(new ReviewNotFoundException("Review not found with id: 99"));

        mockMvc.perform(patch("/api/v1/reviews/99/moderate").param("status", "REJECTED")
                        .header("X-Username", "root").header("X-User-Role", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    /**
     * The endpoint is documented "(Admin)" but neither the controller nor the
     * service looks at X-User-Role, and the gateway only checks that a JWT is
     * present — any logged-in customer can approve their own review.
     */
    @Test
    void moderate_byNonAdmin_returns403() throws Exception {
        when(reviewService.moderateReview(anyLong(), any())).thenReturn(review(11L, "alice", ReviewStatus.APPROVED));

        mockMvc.perform(patch("/api/v1/reviews/11/moderate").param("status", "APPROVED")
                        .header("X-Username", "alice").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());
        verify(reviewService, never()).moderateReview(anyLong(), any());
    }

    // ───────────────────────── votes ─────────────────────────

    @Test
    void vote_passesVoterAndFlag() throws Exception {
        ReviewDTO voted = ReviewDTO.builder().id(11L).helpfulVotes(1).unhelpfulVotes(0).build();
        when(reviewService.voteOnReview(11L, "bob", true)).thenReturn(voted);

        mockMvc.perform(post("/api/v1/reviews/11/vote").header("X-Username", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"helpful\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpfulVotes").value(1))
                .andExpect(jsonPath("$.unhelpfulVotes").value(0));
    }

    @Test
    void vote_unhelpful() throws Exception {
        when(reviewService.voteOnReview(11L, "bob", false)).thenReturn(ReviewDTO.builder().id(11L).unhelpfulVotes(1).build());

        mockMvc.perform(post("/api/v1/reviews/11/vote").header("X-Username", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"helpful\":false}"))
                .andExpect(status().isOk());

        verify(reviewService).voteOnReview(11L, "bob", false);
    }

    @Test
    void vote_missingFlag_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/11/vote").header("X-Username", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("helpful"));
    }

    @Test
    void vote_reviewNotFound_returns404() throws Exception {
        when(reviewService.voteOnReview(99L, "bob", true)).thenThrow(new ReviewNotFoundException("Review not found with id: 99"));

        mockMvc.perform(post("/api/v1/reviews/99/vote").header("X-Username", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"helpful\":true}"))
                .andExpect(status().isNotFound());
    }
}
