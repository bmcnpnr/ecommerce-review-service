package com.ecommerce.reviewservice.service;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.dto.*;
import com.ecommerce.reviewservice.exception.*;
import com.ecommerce.reviewservice.model.*;
import com.ecommerce.reviewservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewVoteRepository reviewVoteRepository;
    private final ProductServiceClient productServiceClient;

    @Transactional
    public ReviewDTO createReview(CreateReviewRequest request, String customerId) {
        // Validate product exists
        try {
            productServiceClient.getProductById(request.getProductId());
        } catch (Exception e) {
            throw new RuntimeException("Product " + request.getProductId() + " not found or product service unavailable");
        }

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

        log.info("Creating review for product {} by customer {}", request.getProductId(), customerId);
        return toDTO(reviewRepository.save(review));
    }

    public ReviewDTO getReviewById(Long id) {
        return toDTO(findById(id));
    }

    public List<ReviewDTO> getReviewsByProduct(Long productId, boolean approvedOnly) {
        List<Review> reviews = approvedOnly
            ? reviewRepository.findByProductIdAndStatus(productId, ReviewStatus.APPROVED)
            : reviewRepository.findByProductId(productId);
        return reviews.stream().map(this::toDTO).toList();
    }

    public List<ReviewDTO> getReviewsByCustomer(String customerId) {
        return reviewRepository.findByCustomerId(customerId).stream().map(this::toDTO).toList();
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
        boolean isAdmin = "ADMIN".equalsIgnoreCase(userRole);
        if (!isAdmin && !review.getCustomerId().equals(customerId)) {
            throw new UnauthorizedReviewAccessException("Only the review author or an admin can delete this review");
        }
        reviewRepository.delete(review);
        log.info("Deleted review {} by customer {} (role: {})", id, customerId, userRole);
    }

    @Transactional
    public ReviewDTO moderateReview(Long id, ReviewStatus newStatus) {
        Review review = findById(id);
        review.setStatus(newStatus);
        review.setUpdatedAt(LocalDateTime.now());
        log.info("Moderated review {} to status {}", id, newStatus);
        return toDTO(reviewRepository.save(review));
    }

    public ReviewSummaryDTO getProductReviewSummary(Long productId) {
        List<Review> approved = reviewRepository.findByProductIdAndStatus(productId, ReviewStatus.APPROVED);
        double average = approved.stream().mapToInt(r -> r.getRating()).average().orElse(0.0);
        Map<Integer, Long> distribution = approved.stream()
            .collect(Collectors.groupingBy(r -> (int) r.getRating(), Collectors.counting()));
        // fill missing ratings with 0
        for (int i = 1; i <= 5; i++) distribution.putIfAbsent(i, 0L);

        return ReviewSummaryDTO.builder()
            .productId(productId)
            .averageRating(Math.round(average * 10.0) / 10.0)
            .totalReviews(reviewRepository.findByProductId(productId).size())
            .approvedReviews(approved.size())
            .ratingDistribution(distribution)
            .build();
    }

    @Transactional
    public ReviewDTO voteOnReview(Long reviewId, String customerId, boolean helpful) {
        Review review = findById(reviewId);
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
        return toDTO(reviewRepository.findById(reviewId).orElseThrow());
    }

    private Review findById(Long id) {
        return reviewRepository.findById(id)
            .orElseThrow(() -> new ReviewNotFoundException("Review not found with id: " + id));
    }

    private ReviewDTO toDTO(Review review) {
        long helpfulVotes = review.getVotes().stream().filter(ReviewVote::getHelpful).count();
        long unhelpfulVotes = review.getVotes().stream().filter(v -> !v.getHelpful()).count();
        return ReviewDTO.builder()
            .id(review.getId())
            .productId(review.getProductId())
            .customerId(review.getCustomerId())
            .rating(review.getRating())
            .title(review.getTitle())
            .body(review.getBody())
            .status(review.getStatus())
            .helpfulVotes(helpfulVotes)
            .unhelpfulVotes(unhelpfulVotes)
            .createdAt(review.getCreatedAt())
            .updatedAt(review.getUpdatedAt())
            .build();
    }
}
