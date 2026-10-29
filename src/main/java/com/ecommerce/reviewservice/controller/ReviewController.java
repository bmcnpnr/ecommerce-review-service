package com.ecommerce.reviewservice.controller;

import com.ecommerce.reviewservice.dto.*;
import com.ecommerce.reviewservice.exception.InvalidRequestException;
import com.ecommerce.reviewservice.exception.UnauthenticatedException;
import com.ecommerce.reviewservice.exception.UnauthorizedReviewAccessException;
import com.ecommerce.reviewservice.model.ReviewStatus;
import com.ecommerce.reviewservice.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Identity comes from the gateway-set headers {@code X-Username} and {@code X-User-Role}.
 * The role is compared exactly ({@code ADMIN}); writes need a non-blank username (401 otherwise);
 * PENDING and REJECTED reviews are visible only to their author and to admins (S-33).
 */
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Product review management endpoints")
public class ReviewController {

    /** Length of the {@code customer_id} column. */
    private static final int MAX_USERNAME_LENGTH = 255;

    private final ReviewService reviewService;

    @PostMapping
    @Operation(summary = "Create a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> createReview(
            @Valid @RequestBody CreateReviewRequest request,
            @RequestHeader(value = "X-Username", required = false) String username) {
        String customerId = requireIdentity(username);
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(request, customerId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get review by ID (unapproved reviews: author or admin only)")
    public ResponseEntity<ReviewDTO> getById(
            @PathVariable Long id,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        ReviewDTO review = reviewService.getReviewById(id);
        if (!canSee(review, username, userRole)) {
            if (isBlank(username)) {
                throw new UnauthenticatedException("Authentication required");
            }
            throw new UnauthorizedReviewAccessException("Access denied");
        }
        return ResponseEntity.ok(review);
    }

    @GetMapping("/product/{productId}")
    @Operation(summary = "Get reviews by product (approvedOnly=false is honoured for admins only)")
    public ResponseEntity<List<ReviewDTO>> getByProduct(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "true") boolean approvedOnly,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        boolean effectiveApprovedOnly = approvedOnly || !isAdmin(userRole);
        return ResponseEntity.ok(reviewService.getReviewsByProduct(productId, effectiveApprovedOnly));
    }

    @GetMapping("/product/{productId}/summary")
    @Operation(summary = "Get review summary for a product")
    public ResponseEntity<ReviewSummaryDTO> getSummary(@PathVariable Long productId) {
        return ResponseEntity.ok(reviewService.getProductReviewSummary(productId));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get reviews by customer (unapproved reviews: that customer or admin only)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<ReviewDTO>> getByCustomer(
            @PathVariable String customerId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        List<ReviewDTO> reviews = reviewService.getReviewsByCustomer(customerId);
        return ResponseEntity.ok(reviews.stream().filter(r -> canSee(r, username, userRole)).toList());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> updateReview(
            @PathVariable Long id,
            @Valid @RequestBody UpdateReviewRequest request,
            @RequestHeader(value = "X-Username", required = false) String username) {
        return ResponseEntity.ok(reviewService.updateReview(id, request, requireIdentity(username)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> deleteReview(
            @PathVariable Long id,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        reviewService.deleteReview(id, requireIdentity(username), userRole);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/moderate")
    @Operation(summary = "Moderate a review (Admin)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> moderate(
            @PathVariable Long id,
            @RequestParam ReviewStatus status,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        if (!isAdmin(userRole)) {
            if (isBlank(username)) {
                throw new UnauthenticatedException("Authentication required");
            }
            throw new UnauthorizedReviewAccessException("Access denied");
        }
        return ResponseEntity.ok(reviewService.moderateReview(id, status));
    }

    @PostMapping("/{id}/vote")
    @Operation(summary = "Vote on a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> vote(
            @PathVariable Long id,
            @Valid @RequestBody VoteRequest request,
            @RequestHeader(value = "X-Username", required = false) String username) {
        return ResponseEntity.ok(reviewService.voteOnReview(id, requireIdentity(username), request.getHelpful()));
    }

    /** Exactly {@code ADMIN}: no trimming, no case folding, no {@code ROLE_} prefix. */
    private static boolean isAdmin(String role) {
        return "ADMIN".equals(role);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean canSee(ReviewDTO review, String username, String role) {
        return review.getStatus() == ReviewStatus.APPROVED
                || isAdmin(role)
                || (!isBlank(username) && username.equals(review.getCustomerId()));
    }

    private static String requireIdentity(String username) {
        if (isBlank(username)) {
            throw new UnauthenticatedException("Authentication required");
        }
        if (username.length() > MAX_USERNAME_LENGTH || username.indexOf(0) >= 0) {
            throw new InvalidRequestException("Invalid X-Username header");
        }
        return username;
    }
}
