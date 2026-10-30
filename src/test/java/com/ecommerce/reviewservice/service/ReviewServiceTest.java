package com.ecommerce.reviewservice.service;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.dto.*;
import com.ecommerce.reviewservice.exception.*;
import com.ecommerce.reviewservice.model.*;
import com.ecommerce.reviewservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock ReviewRepository reviewRepository;
    @Mock ReviewVoteRepository reviewVoteRepository;
    @Mock ProductServiceClient productServiceClient;
    @InjectMocks ReviewService reviewService;

    private ProductDTO productDTO;

    @BeforeEach
    void setup() {
        productDTO = new ProductDTO();
        productDTO.setId(1L);
        productDTO.setSku("SKU-001");
        productDTO.setName("Test Product");
        productDTO.setPrice(BigDecimal.valueOf(99.99));
    }

    @Test
    void createReview_success() {
        when(productServiceClient.getProductById(1L)).thenReturn(productDTO);
        when(reviewRepository.findByProductIdAndCustomerId(1L, "user1")).thenReturn(Optional.empty());

        Review saved = Review.builder().id(1L).productId(1L).customerId("user1")
            .rating((short)5).title("Great").body("Really great product").status(ReviewStatus.PENDING).build();
        when(reviewRepository.save(any())).thenReturn(saved);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setProductId(1L);
        request.setRating((short)5);
        request.setTitle("Great");
        request.setBody("Really great product");

        ReviewDTO result = reviewService.createReview(request, "user1");
        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getRating()).isEqualTo((short)5);
    }

    @Test
    void createReview_duplicateThrows() {
        when(productServiceClient.getProductById(1L)).thenReturn(productDTO);
        Review existing = Review.builder().id(1L).productId(1L).customerId("user1").build();
        when(reviewRepository.findByProductIdAndCustomerId(1L, "user1")).thenReturn(Optional.of(existing));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setProductId(1L);
        request.setRating((short)4);
        request.setTitle("Good");
        request.setBody("Pretty good product overall");

        assertThatThrownBy(() -> reviewService.createReview(request, "user1"))
            .isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    void deleteReview_byNonOwnerNonAdmin_throws() {
        Review review = Review.builder().id(1L).customerId("owner").build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.deleteReview(1L, "other-user", "CUSTOMER"))
            .isInstanceOf(UnauthorizedReviewAccessException.class);
    }

    @Test
    void deleteReview_byAdmin_success() {
        Review review = Review.builder().id(1L).customerId("owner").build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

        assertThatCode(() -> reviewService.deleteReview(1L, "admin-user", "ADMIN"))
            .doesNotThrowAnyException();
        verify(reviewRepository).delete(review);
    }

    @Test
    void getReviewById_notFound_throws() {
        when(reviewRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reviewService.getReviewById(99L))
            .isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void getProductReviewSummary_noReviews_returnsZero() {
        ReviewSummaryDTO summary = reviewService.getProductReviewSummary(1L);

        assertThat(summary.getAverageRating()).isEqualTo(0.0);
        assertThat(summary.getTotalReviews()).isEqualTo(0);
        assertThat(summary.getRatingDistribution()).containsOnlyKeys(1, 2, 3, 4, 5);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static CreateReviewRequest createRequest(long productId) {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setProductId(productId);
        request.setRating((short) 4);
        request.setTitle("Good");
        request.setBody("Pretty good product overall");
        return request;
    }

    private static Review review(long id, String customer, int rating, ReviewStatus status) {
        return Review.builder().id(id).productId(1L).customerId(customer).rating((short) rating)
            .title("t" + id).body("body of review " + id).status(status).build();
    }

    // ───────────────────────── create ─────────────────────────

    @Test
    void createReview_storesPendingReviewWithRequestFields() {
        when(productServiceClient.getProductById(1L)).thenReturn(productDTO);
        when(reviewRepository.findByProductIdAndCustomerId(1L, "user1")).thenReturn(Optional.empty());
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reviewService.createReview(createRequest(1L), "user1");

        org.mockito.ArgumentCaptor<Review> captor = org.mockito.ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        Review saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(saved.getCustomerId()).isEqualTo("user1");
        assertThat(saved.getRating()).isEqualTo((short) 4);
        assertThat(saved.getTitle()).isEqualTo("Good");
        assertThat(saved.getBody()).isEqualTo("Pretty good product overall");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void createReview_unknownProduct_isAProductNotFound_andStoresNothing() {
        when(productServiceClient.getProductById(999L)).thenThrow(new ProductNotFoundException("Product not found with id: 999"));

        assertThatThrownBy(() -> reviewService.createReview(createRequest(999L), "user1"))
            .isExactlyInstanceOf(ProductNotFoundException.class);
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void createReview_productServiceDown_isAnOutage_notABug_andStoresNothing() {
        when(productServiceClient.getProductById(1L))
            .thenAnswer(inv -> new com.ecommerce.reviewservice.client.ProductServiceClientFallback().getProductById(1L));

        assertThatThrownBy(() -> reviewService.createReview(createRequest(1L), "user1"))
            .isExactlyInstanceOf(ProductServiceUnavailableException.class);
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void createReview_anUnexpectedClientFailure_isNotDisguisedAsAnotherError() {
        when(productServiceClient.getProductById(1L)).thenThrow(new IllegalStateException("bug in the client"));

        assertThatThrownBy(() -> reviewService.createReview(createRequest(1L), "user1"))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("bug in the client");
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void createReview_doesNotHoldATransactionAroundTheRemoteCall() throws Exception {
        // A slow product-service must not pin a pooled database connection (H2): no @Transactional on the method.
        assertThat(ReviewService.class.getMethod("createReview", CreateReviewRequest.class, String.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(ReviewService.class.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    // ───────────────────────── reads ─────────────────────────

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 100, Sort.by("id"));

    private static ReviewVoteRepository.VoteCount votes(long reviewId, boolean helpful, long total) {
        return new ReviewVoteRepository.VoteCount() {
            public Long getReviewId() { return reviewId; }
            public Boolean getHelpful() { return helpful; }
            public long getTotal() { return total; }
        };
    }

    @Test
    void getReviewsByProduct_approvedOnly_queriesApprovedStatus() {
        when(reviewRepository.findByProductIdAndStatus(1L, ReviewStatus.APPROVED, FIRST_PAGE))
            .thenReturn(List.of(review(1L, "a", 5, ReviewStatus.APPROVED)));

        assertThat(reviewService.getReviewsByProduct(1L, true, FIRST_PAGE)).hasSize(1);
        verify(reviewRepository, never()).findByProductId(anyLong(), any(Pageable.class));
    }

    @Test
    void getReviewsByProduct_all_queriesEveryStatus() {
        when(reviewRepository.findByProductId(1L, FIRST_PAGE)).thenReturn(List.of(
            review(1L, "a", 5, ReviewStatus.APPROVED), review(2L, "b", 2, ReviewStatus.PENDING),
            review(3L, "c", 1, ReviewStatus.REJECTED)));

        assertThat(reviewService.getReviewsByProduct(1L, false, FIRST_PAGE))
            .extracting(ReviewDTO::getStatus)
            .containsExactly(ReviewStatus.APPROVED, ReviewStatus.PENDING, ReviewStatus.REJECTED);
        verify(reviewRepository, never()).findByProductIdAndStatus(anyLong(), any(), any(Pageable.class));
    }

    @Test
    void getReviewsByProduct_neverLoadsAnUnboundedList() {
        when(reviewRepository.findByProductIdAndStatus(1L, ReviewStatus.APPROVED, FIRST_PAGE)).thenReturn(List.of());

        assertThat(reviewService.getReviewsByProduct(1L, true, FIRST_PAGE)).isEmpty();

        verify(reviewRepository, never()).findByProductId(anyLong());
        verify(reviewRepository, never()).findByProductIdAndStatus(anyLong(), any());
        verifyNoInteractions(reviewVoteRepository);   // an empty page asks for no votes at all
    }

    @Test
    void getReviewsByCustomer_publishedOnlyOrEverything() {
        when(reviewRepository.findByCustomerIdAndStatus("a", ReviewStatus.APPROVED, FIRST_PAGE))
            .thenReturn(List.of(review(1L, "a", 5, ReviewStatus.APPROVED)));
        when(reviewRepository.findByCustomerId("a", FIRST_PAGE))
            .thenReturn(List.of(review(1L, "a", 5, ReviewStatus.APPROVED), review(2L, "a", 3, ReviewStatus.PENDING)));

        assertThat(reviewService.getReviewsByCustomer("a", true, FIRST_PAGE)).extracting(ReviewDTO::getId).containsExactly(1L);
        assertThat(reviewService.getReviewsByCustomer("a", false, FIRST_PAGE)).extracting(ReviewDTO::getId).containsExactly(1L, 2L);
    }

    @Test
    void getReviewById_countsHelpfulAndUnhelpfulVotes() {
        Review r = review(1L, "a", 5, ReviewStatus.APPROVED);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));
        when(reviewVoteRepository.countByReview(List.of(1L))).thenReturn(List.of(votes(1L, true, 2), votes(1L, false, 1)));

        ReviewDTO dto = reviewService.getReviewById(1L);

        assertThat(dto.getHelpfulVotes()).isEqualTo(2);
        assertThat(dto.getUnhelpfulVotes()).isEqualTo(1);
    }

    @Test
    void aPageOfReviews_isCountedWithOneVoteQuery_notOnePerReview() {
        when(reviewRepository.findByProductIdAndStatus(1L, ReviewStatus.APPROVED, FIRST_PAGE)).thenReturn(List.of(
            review(1L, "a", 5, ReviewStatus.APPROVED), review(2L, "b", 4, ReviewStatus.APPROVED),
            review(3L, "c", 3, ReviewStatus.APPROVED)));
        when(reviewVoteRepository.countByReview(List.of(1L, 2L, 3L))).thenReturn(List.of(
            votes(1L, true, 7), votes(3L, true, 1), votes(3L, false, 4)));

        List<ReviewDTO> page = reviewService.getReviewsByProduct(1L, true, FIRST_PAGE);

        assertThat(page).extracting(ReviewDTO::getHelpfulVotes).containsExactly(7L, 0L, 1L);
        assertThat(page).extracting(ReviewDTO::getUnhelpfulVotes).containsExactly(0L, 0L, 4L);
        verify(reviewVoteRepository, times(1)).countByReview(any());
        verifyNoMoreInteractions(reviewVoteRepository);
    }

    // ───────────────────────── update / delete ─────────────────────────

    @Test
    void updateReview_byOwner_appliesNonNullFields_andResetsToPending() {
        Review r = review(1L, "owner", 5, ReviewStatus.APPROVED);
        java.time.LocalDateTime before = r.getUpdatedAt();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setRating((short) 2);

        ReviewDTO dto = reviewService.updateReview(1L, request, "owner");

        assertThat(dto.getRating()).isEqualTo((short) 2);
        assertThat(dto.getTitle()).isEqualTo("t1");
        assertThat(dto.getBody()).isEqualTo("body of review 1");
        assertThat(dto.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(dto.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void updateReview_allFields() {
        Review r = review(1L, "owner", 5, ReviewStatus.PENDING);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setRating((short) 3);
        request.setTitle("New title");
        request.setBody("A completely new body text");

        ReviewDTO dto = reviewService.updateReview(1L, request, "owner");

        assertThat(dto).extracting(ReviewDTO::getRating, ReviewDTO::getTitle, ReviewDTO::getBody)
            .containsExactly((short) 3, "New title", "A completely new body text");
    }

    @Test
    void updateReview_byNonOwner_throwsAndDoesNotSave() {
        Review r = review(1L, "owner", 5, ReviewStatus.APPROVED);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reviewService.updateReview(1L, new UpdateReviewRequest(), "intruder"))
            .isInstanceOf(UnauthorizedReviewAccessException.class);
        assertThat(r.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void updateReview_notFound_throws() {
        when(reviewRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.updateReview(9L, new UpdateReviewRequest(), "x"))
            .isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void deleteReview_byOwner_deletes() {
        Review r = review(1L, "owner", 5, ReviewStatus.APPROVED);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));

        reviewService.deleteReview(1L, "owner", "CUSTOMER");

        verify(reviewRepository).delete(r);
    }

    @Test
    void deleteReview_adminRoleIsCaseSensitive() {
        Review r = review(1L, "owner", 5, ReviewStatus.APPROVED);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reviewService.deleteReview(1L, "root", "admin"))
            .isInstanceOf(UnauthorizedReviewAccessException.class);

        verify(reviewRepository, never()).delete(any());
    }

    @Test
    void deleteReview_nonOwnerWithBlankRole_throws() {
        Review r = review(1L, "owner", 5, ReviewStatus.APPROVED);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> reviewService.deleteReview(1L, "someone", ""))
            .isInstanceOf(UnauthorizedReviewAccessException.class);
        verify(reviewRepository, never()).delete(any());
    }

    @Test
    void deleteReview_notFound_throws() {
        when(reviewRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.deleteReview(9L, "x", "ADMIN"))
            .isInstanceOf(ReviewNotFoundException.class);
    }

    // ───────────────────────── moderation ─────────────────────────

    @Test
    void moderateReview_setsEachStatus() {
        Review r = review(1L, "a", 5, ReviewStatus.PENDING);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(reviewService.moderateReview(1L, ReviewStatus.APPROVED).getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(reviewService.moderateReview(1L, ReviewStatus.REJECTED).getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(reviewService.moderateReview(1L, ReviewStatus.PENDING).getStatus()).isEqualTo(ReviewStatus.PENDING);
        verify(reviewRepository, times(3)).save(r);
    }

    @Test
    void moderateReview_notFound_throws() {
        when(reviewRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.moderateReview(9L, ReviewStatus.APPROVED))
            .isInstanceOf(ReviewNotFoundException.class);
        verify(reviewRepository, never()).save(any());
    }

    // ───────────────────────── summary maths ─────────────────────────

    private static ReviewRepository.RatingCount ratings(int rating, long total) {
        return new ReviewRepository.RatingCount() {
            public Short getRating() { return (short) rating; }
            public long getTotal() { return total; }
        };
    }

    @Test
    void summary_isComputedFromCounts_distributionHasAllFiveBuckets_totalCountsEveryStatus() {
        when(reviewRepository.countRatings(1L, ReviewStatus.APPROVED))
            .thenReturn(List.of(ratings(5, 1), ratings(4, 2), ratings(1, 1)));
        when(reviewRepository.countByProductIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(4L);
        when(reviewRepository.countByProductId(1L)).thenReturn(6L);

        ReviewSummaryDTO summary = reviewService.getProductReviewSummary(1L);

        assertThat(summary.getProductId()).isEqualTo(1L);
        assertThat(summary.getAverageRating()).isEqualTo(3.5);
        assertThat(summary.getApprovedReviews()).isEqualTo(4);
        assertThat(summary.getTotalReviews()).isEqualTo(6);
        assertThat(summary.getRatingDistribution())
            .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(1, 1L, 2, 0L, 3, 0L, 4, 2L, 5, 1L));
    }

    @Test
    void summary_neverLoadsTheReviewsThemselves() {
        reviewService.getProductReviewSummary(1L);

        verify(reviewRepository, never()).findByProductId(anyLong());
        verify(reviewRepository, never()).findByProductIdAndStatus(anyLong(), any());
        verify(reviewRepository, never()).findAll();
    }

    @Test
    void summary_roundsAverageToOneDecimal() {
        assertThat(averageOf(5, 4, 4)).isEqualTo(4.3);   // 4.333…
        assertThat(averageOf(5, 5, 4)).isEqualTo(4.7);   // 4.666…
        assertThat(averageOf(5, 4)).isEqualTo(4.5);
        assertThat(averageOf(1, 2, 2)).isEqualTo(1.7);   // 1.666…
        assertThat(averageOf(3)).isEqualTo(3.0);
    }

    @Test
    void summary_roundsExactHalvesUp() {
        assertThat(averageOfCounts(ratings(1, 1), ratings(2, 1), ratings(3, 2))).as("9 / 4 = 2.25").isEqualTo(2.3);
        assertThat(averageOfCounts(ratings(1, 1), ratings(2, 7))).as("15 / 8 = 1.875").isEqualTo(1.9);
        assertThat(averageOfCounts(ratings(5, 1), ratings(4, 3))).as("17 / 4 = 4.25").isEqualTo(4.3);
        assertThat(averageOfCounts(ratings(1, 19), ratings(2, 1))).as("21 / 20 = 1.05").isEqualTo(1.1);
        assertThat(averageOfCounts(ratings(2, 1), ratings(3, 1))).as("5 / 2 = 2.5, nothing to round").isEqualTo(2.5);
    }

    @Test
    void summary_largeCounts_doNotOverflowOrLoseAPoint() {
        long many = 3_000_000_000L; // more than an int holds
        assertThat(averageOfCounts(ratings(5, many), ratings(4, many))).isEqualTo(4.5);
    }

    @Test
    void summary_noApproved_butPendingExists() {
        when(reviewRepository.countByProductId(1L)).thenReturn(1L);

        ReviewSummaryDTO summary = reviewService.getProductReviewSummary(1L);

        assertThat(summary.getAverageRating()).isZero();
        assertThat(summary.getApprovedReviews()).isZero();
        assertThat(summary.getTotalReviews()).isEqualTo(1);
        assertThat(summary.getRatingDistribution()).hasSize(5).allSatisfy((k, v) -> assertThat(v).isZero());
    }

    @Test
    void summary_aRatingOutsideOneToFive_cannotSkewTheMean() {
        // the CHECK constraint makes this impossible in Postgres; the H2 profile has none
        assertThat(averageOfCounts(ratings(0, 100), ratings(4, 2), ratings(5, 2))).isEqualTo(4.5);
    }

    private double averageOf(int... ratings) {
        java.util.Map<Integer, Long> counts = new java.util.TreeMap<>();
        for (int rating : ratings) counts.merge(rating, 1L, Long::sum);
        return averageOfCounts(counts.entrySet().stream().map(e -> ratings(e.getKey(), e.getValue()))
            .toArray(ReviewRepository.RatingCount[]::new));
    }

    private double averageOfCounts(ReviewRepository.RatingCount... counts) {
        when(reviewRepository.countRatings(1L, ReviewStatus.APPROVED)).thenReturn(List.of(counts));
        return reviewService.getProductReviewSummary(1L).getAverageRating();
    }

    // ───────────────────────── votes ─────────────────────────

    @Test
    void vote_firstVote_savesNewVote() {
        Review r = review(1L, "author", 5, ReviewStatus.APPROVED);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));
        when(reviewVoteRepository.findByReviewIdAndCustomerId(1L, "voter")).thenReturn(Optional.empty());

        reviewService.voteOnReview(1L, "voter", true);

        org.mockito.ArgumentCaptor<ReviewVote> captor = org.mockito.ArgumentCaptor.forClass(ReviewVote.class);
        verify(reviewVoteRepository).save(captor.capture());
        assertThat(captor.getValue().getReview()).isSameAs(r);
        assertThat(captor.getValue().getCustomerId()).isEqualTo("voter");
        assertThat(captor.getValue().getHelpful()).isTrue();
        assertThat(captor.getValue().getVotedAt()).isNotNull();
    }

    @Test
    void vote_repeatVote_updatesExistingInsteadOfAddingAnother() {
        Review r = review(1L, "author", 5, ReviewStatus.APPROVED);
        ReviewVote existing = ReviewVote.builder().id(7L).review(r).customerId("voter").helpful(true).build();
        r.getVotes().add(existing);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(r));
        when(reviewVoteRepository.findByReviewIdAndCustomerId(1L, "voter")).thenReturn(Optional.of(existing));
        when(reviewVoteRepository.countByReview(List.of(1L))).thenReturn(List.of(votes(1L, false, 1)));

        ReviewDTO dto = reviewService.voteOnReview(1L, "voter", false);

        assertThat(existing.getHelpful()).isFalse();
        verify(reviewVoteRepository).save(existing);
        verify(reviewVoteRepository, times(1)).save(any());
        assertThat(dto.getHelpfulVotes()).isZero();
        assertThat(dto.getUnhelpfulVotes()).isEqualTo(1);
    }

    @Test
    void vote_reviewNotFound_throwsAndSavesNothing() {
        when(reviewRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.voteOnReview(9L, "voter", true))
            .isInstanceOf(ReviewNotFoundException.class);
        verifyNoInteractions(reviewVoteRepository);
    }
}
