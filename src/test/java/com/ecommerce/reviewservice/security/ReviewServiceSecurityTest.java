package com.ecommerce.reviewservice.security;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.dto.CreateReviewRequest;
import com.ecommerce.reviewservice.dto.ReviewDTO;
import com.ecommerce.reviewservice.dto.UpdateReviewRequest;
import com.ecommerce.reviewservice.exception.UnauthorizedReviewAccessException;
import com.ecommerce.reviewservice.model.Review;
import com.ecommerce.reviewservice.model.ReviewStatus;
import com.ecommerce.reviewservice.model.ReviewVote;
import com.ecommerce.reviewservice.repository.ReviewRepository;
import com.ecommerce.reviewservice.repository.ReviewVoteRepository;
import com.ecommerce.reviewservice.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Ownership, role and vote rules inside {@link ReviewService}, against mocked repositories. */
@Tag("security")
class ReviewServiceSecurityTest {

    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewVoteRepository votes = mock(ReviewVoteRepository.class);
    private final ProductServiceClient products = mock(ProductServiceClient.class);
    private final ReviewService service = new ReviewService(reviews, votes, products);

    private Review aliceReview;

    @BeforeEach
    void setUp() {
        aliceReview = Review.builder().id(11L).productId(1L).customerId("alice").rating((short) 5)
                .title("Great mouse").body("Comfortable and precise, battery lasts.").status(ReviewStatus.APPROVED).build();
        when(reviews.findById(11L)).thenReturn(Optional.of(aliceReview));
        when(reviews.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ───────────────────────── create ─────────────────────────

    @Test
    void create_alwaysStartsPending_andIsOwnedByTheCaller() {
        when(reviews.findByProductIdAndCustomerId(1L, "mallory")).thenReturn(Optional.empty());
        CreateReviewRequest request = new CreateReviewRequest();
        request.setProductId(1L);
        request.setRating((short) 5);
        request.setTitle("Great mouse");
        request.setBody("Comfortable and precise, battery lasts.");

        ReviewDTO created = service.createReview(request, "mallory");

        assertThat(created.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(created.getCustomerId()).isEqualTo("mallory");
        assertThat(created.getHelpfulVotes()).isZero();
    }

    // ───────────────────────── update ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"mallory", "Alice", "ALICE", "alice ", " alice", "alice\u0000", "alicе", "anonymous", ""})
    void update_byAnyoneButTheExactAuthor_isForbidden_andNothingIsSaved(String caller) {
        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setRating((short) 1);

        assertThatThrownBy(() -> service.updateReview(11L, request, caller))
                .isInstanceOf(UnauthorizedReviewAccessException.class);

        verify(reviews, never()).save(any());
        assertThat(aliceReview.getRating()).isEqualTo((short) 5);
        assertThat(aliceReview.getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void update_byAuthor_sendsTheReviewBackToModeration_andKeepsOwnerAndProduct() {
        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setBody("Changed my mind after a month of daily use.");

        ReviewDTO updated = service.updateReview(11L, request, "alice");

        // an approved review cannot be silently rewritten (e.g. into spam) and stay published
        assertThat(updated.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(updated.getCustomerId()).isEqualTo("alice");
        assertThat(updated.getProductId()).isEqualTo(1L);
    }

    @Test
    void update_byAdmin_isStillAuthorOnly() {
        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setTitle("Edited by the boss");

        assertThatThrownBy(() -> service.updateReview(11L, request, "root"))
                .isInstanceOf(UnauthorizedReviewAccessException.class);
        verify(reviews, never()).save(any());
    }

    // ───────────────────────── delete ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "", "ROLE_ADMIN", "ADMINX", " ADMIN", "ADMIN ", "null"})
    void delete_byAnotherUser_withoutExactAdminRole_isForbidden(String role) {
        assertThatThrownBy(() -> service.deleteReview(11L, "mallory", role))
                .isInstanceOf(UnauthorizedReviewAccessException.class);

        verify(reviews, never()).delete(any());
    }

    @Test
    void delete_byAdmin_isAllowed_andByAuthor_isAllowed() {
        service.deleteReview(11L, "root", "ADMIN");
        service.deleteReview(11L, "alice", "CUSTOMER");

        verify(reviews, times(2)).delete(aliceReview);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Admin"})
    @Disabled("BUG S-30: the role check is case-insensitive (equalsIgnoreCase), so a caller with role 'admin' deletes anyone's review")
    void delete_byAnotherUser_withWrongCaseAdminRole_isForbidden(String role) {
        assertThatThrownBy(() -> service.deleteReview(11L, "mallory", role))
                .isInstanceOf(UnauthorizedReviewAccessException.class);

        verify(reviews, never()).delete(any());
    }

    // ───────────────────────── votes ─────────────────────────

    @Test
    @Disabled("BUG S-32: an author can vote their own review 'helpful', inflating the score used to rank it")
    void vote_onOwnReview_isRejected() {
        assertThatThrownBy(() -> service.voteOnReview(11L, "alice", true))
                .isInstanceOf(UnauthorizedReviewAccessException.class);

        verify(votes, never()).save(any());
    }

    @Test
    @Disabled("BUG S-32: votes are accepted on PENDING/REJECTED reviews, which are not public and have not been moderated")
    void vote_onUnapprovedReview_isRejected() {
        for (ReviewStatus status : new ReviewStatus[]{ReviewStatus.PENDING, ReviewStatus.REJECTED}) {
            aliceReview.setStatus(status);

            assertThatThrownBy(() -> service.voteOnReview(11L, "bob", true)).as("review is %s", status)
                    .isInstanceOf(RuntimeException.class);
        }

        verify(votes, never()).save(any());
    }

    @Test
    void vote_repeatedBySameUser_updatesTheSingleVote_neverAddsAnother() {
        when(votes.findByReviewIdAndCustomerId(11L, "bob")).thenReturn(Optional.empty());
        when(reviews.findById(11L)).thenReturn(Optional.of(aliceReview));

        service.voteOnReview(11L, "bob", true);

        ArgumentCaptor<ReviewVote> first = ArgumentCaptor.forClass(ReviewVote.class);
        verify(votes).save(first.capture());
        ReviewVote bobsVote = first.getValue();
        when(votes.findByReviewIdAndCustomerId(11L, "bob")).thenReturn(Optional.of(bobsVote));

        for (int i = 0; i < 20; i++) {
            service.voteOnReview(11L, "bob", i % 2 == 0);
        }

        // every later vote re-saves the SAME row (the DB also has a unique (review, customer) constraint)
        verify(votes, times(21)).save(any());
        verify(votes, times(21)).save(bobsVote);
        assertThat(bobsVote.getCustomerId()).isEqualTo("bob");
    }

    // ───────────────────────── moderation ─────────────────────────

    @Test
    void moderate_changesOnlyTheStatus() {
        ReviewDTO moderated = service.moderateReview(11L, ReviewStatus.REJECTED);

        assertThat(moderated.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(moderated.getCustomerId()).isEqualTo("alice");
        assertThat(moderated.getRating()).isEqualTo((short) 5);
    }
}
