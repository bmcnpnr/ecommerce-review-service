package com.ecommerce.reviewservice.service;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.dto.*;
import com.ecommerce.reviewservice.exception.*;
import com.ecommerce.reviewservice.model.*;
import com.ecommerce.reviewservice.repository.*;
import com.ecommerce.reviewservice.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewVoteRepository reviewVoteRepository;
    private final ProductServiceClient productServiceClient;

    /**
     * Deliberately not {@code @Transactional}: the call to product-service can take seconds (read timeout 10 s,
     * connection retries), and inside a transaction it would pin a pooled database connection for all of it, so a
     * slow peer exhausts the pool. The existence check runs first, with no connection held; the duplicate lookup and
     * the insert are each short repository transactions (the unique constraint, not a transaction, is what
     * stops two concurrent identical reviews, and that race is answered 409).
     *
     * <p>product-service failures arrive as typed exceptions from the Feign fallback: 404 for an unknown product,
     * 503 when it gives no answer, 502 when its answer is unusable.
     */
    public ReviewDTO createReview(CreateReviewRequest request, String customerId) {
        productServiceClient.getProductById(request.getProductId());

        // Check for duplicate
        reviewRepository.findByProductIdAndCustomerId(request.getProductId(), customerId)
            .ifPresent(r -> { throw new DuplicateReviewException(
                "Customer " + customerId + " has already reviewed product " + request.getProductId()); });

        Review review = Review.builder()
            .productId(request.getProductId())
            .customerId(customerId)
            .rating(request.getRating())
            .title(request.getTitle())
            .body(request.getBody())
            .status(ReviewStatus.PENDING)
            .build();

        log.info("Creating review for product {} by customer {}", request.getProductId(), LogSanitizer.clean(customerId));
        return toDTO(reviewRepository.save(review), VoteTally.NONE);
    }

    @Transactional(readOnly = true)
    public ReviewDTO getReviewById(Long id) {
        return toDTO(findById(id));
    }

    /** One page of a product's reviews, oldest first; the controller caps the page size. */
    @Transactional(readOnly = true)
    public List<ReviewDTO> getReviewsByProduct(Long productId, boolean approvedOnly, Pageable pageable) {
        List<Review> reviews = approvedOnly
            ? reviewRepository.findByProductIdAndStatus(productId, ReviewStatus.APPROVED, pageable)
            : reviewRepository.findByProductId(productId, pageable);
        return toDTOs(reviews);
    }

    /** One page of a customer's reviews; {@code approvedOnly} is decided by the caller's right to see unpublished ones. */
    @Transactional(readOnly = true)
    public List<ReviewDTO> getReviewsByCustomer(String customerId, boolean approvedOnly, Pageable pageable) {
        List<Review> reviews = approvedOnly
            ? reviewRepository.findByCustomerIdAndStatus(customerId, ReviewStatus.APPROVED, pageable)
            : reviewRepository.findByCustomerId(customerId, pageable);
        return toDTOs(reviews);
    }

    @Transactional
    public ReviewDTO updateReview(Long id, UpdateReviewRequest request, String customerId) {
        Review review = findById(id);
        if (!review.getCustomerId().equals(customerId)) {
            throw new UnauthorizedReviewAccessException("Only the review author can update this review");
        }
        if (request.getRating() != null) review.setRating(request.getRating());
        if (request.getTitle() != null) review.setTitle(request.getTitle());
        if (request.getBody() != null) review.setBody(request.getBody());
        review.setStatus(ReviewStatus.PENDING); // re-moderate on update
        review.setUpdatedAt(LocalDateTime.now());
        return toDTO(reviewRepository.save(review));
    }

    @Transactional
    public void deleteReview(Long id, String customerId, String userRole) {
        Review review = findById(id);
        boolean isAdmin = "ADMIN".equals(userRole);
        if (!isAdmin && !review.getCustomerId().equals(customerId)) {
            throw new UnauthorizedReviewAccessException("Only the review author or an admin can delete this review");
        }
        reviewRepository.delete(review);
        log.info("Deleted review {} by customer {} (admin: {})", id, LogSanitizer.clean(customerId), isAdmin);
    }

    @Transactional
    public ReviewDTO moderateReview(Long id, ReviewStatus newStatus) {
        Review review = findById(id);
        review.setStatus(newStatus);
        review.setUpdatedAt(LocalDateTime.now());
        log.info("Moderated review {} to status {}", id, newStatus);
        return toDTO(reviewRepository.save(review));
    }

    /**
     * Counts instead of loading: the summary is public, shown on every product page, and a product can have any
     * number of reviews, so the database groups them and this method only sees five numbers. The average is
     * computed from those counts in exact decimal arithmetic and rounded half-up to one decimal, so there is no
     * floating-point mean to drift and no count can overflow an int.
     */
    @Transactional(readOnly = true)
    public ReviewSummaryDTO getProductReviewSummary(Long productId) {
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int rating = 1; rating <= 5; rating++) distribution.put(rating, 0L); // keys 1..5 are always present
        for (ReviewRepository.RatingCount count : reviewRepository.countRatings(productId, ReviewStatus.APPROVED)) {
            // only 1..5 can exist (CHECK constraint); anything else would be corrupt data and must not skew the mean
            distribution.computeIfPresent((int) count.getRating(), (rating, ignored) -> count.getTotal());
        }
        long rated = distribution.values().stream().mapToLong(Long::longValue).sum();
        long ratingPoints = distribution.entrySet().stream().mapToLong(e -> e.getKey() * e.getValue()).sum();
        double average = rated == 0 ? 0.0
            : BigDecimal.valueOf(ratingPoints).divide(BigDecimal.valueOf(rated), 1, RoundingMode.HALF_UP).doubleValue();

        return ReviewSummaryDTO.builder()
            .productId(productId)
            .averageRating(average)
            .totalReviews(reviewRepository.countByProductId(productId))
            .approvedReviews(reviewRepository.countByProductIdAndStatus(productId, ReviewStatus.APPROVED))
            .ratingDistribution(distribution)
            .build();
    }

    @Transactional
    public ReviewDTO voteOnReview(Long reviewId, String customerId, boolean helpful) {
        Review review = findById(reviewId);
        // only published reviews take votes; an unpublished one is indistinguishable from a missing one
        if (review.getStatus() != ReviewStatus.APPROVED) {
            throw new ReviewNotFoundException("Review not found with id: " + reviewId);
        }
        if (review.getCustomerId().equals(customerId)) {
            throw new UnauthorizedReviewAccessException("Authors cannot vote on their own review");
        }
        Optional<ReviewVote> existing = reviewVoteRepository.findByReviewIdAndCustomerId(reviewId, customerId);
        if (existing.isPresent()) {
            existing.get().setHelpful(helpful);
            reviewVoteRepository.save(existing.get());
        } else {
            ReviewVote vote = ReviewVote.builder()
                .review(review)
                .customerId(customerId)
                .helpful(helpful)
                .build();
            reviewVoteRepository.save(vote);
        }
        return toDTO(review);
    }

    private Review findById(Long id) {
        return reviewRepository.findById(id)
            .orElseThrow(() -> new ReviewNotFoundException("Review not found with id: " + id));
    }

    private ReviewDTO toDTO(Review review) {
        return toDTOs(List.of(review)).get(0);
    }

    /** Maps a page of reviews with one vote-count query for all of them, not one lazy collection load per review. */
    private List<ReviewDTO> toDTOs(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return List.of();
        }
        Map<Long, VoteTally> tallies = new HashMap<>();
        List<Long> ids = reviews.stream().map(Review::getId).toList();
        for (ReviewVoteRepository.VoteCount count : reviewVoteRepository.countByReview(ids)) {
            tallies.merge(count.getReviewId(), VoteTally.of(count.getHelpful(), count.getTotal()), VoteTally::plus);
        }
        return reviews.stream().map(r -> toDTO(r, tallies.getOrDefault(r.getId(), VoteTally.NONE))).toList();
    }

    private ReviewDTO toDTO(Review review, VoteTally votes) {
        return ReviewDTO.builder()
            .id(review.getId())
            .productId(review.getProductId())
            .customerId(review.getCustomerId())
            .rating(review.getRating())
            .title(review.getTitle())
            .body(review.getBody())
            .status(review.getStatus())
            .helpfulVotes(votes.helpful())
            .unhelpfulVotes(votes.unhelpful())
            .createdAt(review.getCreatedAt())
            .updatedAt(review.getUpdatedAt())
            .build();
    }

    private record VoteTally(long helpful, long unhelpful) {
        static final VoteTally NONE = new VoteTally(0, 0);

        static VoteTally of(Boolean helpful, long total) {
            return Boolean.TRUE.equals(helpful) ? new VoteTally(total, 0) : new VoteTally(0, total);
        }

        VoteTally plus(VoteTally other) {
            return new VoteTally(helpful + other.helpful, unhelpful + other.unhelpful);
        }
    }
}
