package com.ecommerce.reviewservice.controller;

import com.ecommerce.reviewservice.dto.*;
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

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Product review management endpoints")
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    @Operation(summary = "Create a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> createReview(
            @Valid @RequestBody CreateReviewRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "anonymous") String customerId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(request, customerId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get review by ID")
    public ResponseEntity<ReviewDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(reviewService.getReviewById(id));
    }

    @GetMapping("/product/{productId}")
    @Operation(summary = "Get reviews by product")
    public ResponseEntity<List<ReviewDTO>> getByProduct(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "true") boolean approvedOnly) {
        return ResponseEntity.ok(reviewService.getReviewsByProduct(productId, approvedOnly));
    }

    @GetMapping("/product/{productId}/summary")
    @Operation(summary = "Get review summary for a product")
    public ResponseEntity<ReviewSummaryDTO> getSummary(@PathVariable Long productId) {
        return ResponseEntity.ok(reviewService.getProductReviewSummary(productId));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get reviews by customer", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<ReviewDTO>> getByCustomer(@PathVariable String customerId) {
        return ResponseEntity.ok(reviewService.getReviewsByCustomer(customerId));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> updateReview(
            @PathVariable Long id,
            @Valid @RequestBody UpdateReviewRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "anonymous") String customerId) {
        return ResponseEntity.ok(reviewService.updateReview(id, request, customerId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> deleteReview(
            @PathVariable Long id,
            @RequestHeader(value = "X-Username", defaultValue = "anonymous") String customerId,
            @RequestHeader(value = "X-User-Role", defaultValue = "CUSTOMER") String userRole) {
        reviewService.deleteReview(id, customerId, userRole);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/moderate")
    @Operation(summary = "Moderate a review (Admin)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> moderate(
            @PathVariable Long id,
            @RequestParam ReviewStatus status) {
        return ResponseEntity.ok(reviewService.moderateReview(id, status));
    }

    @PostMapping("/{id}/vote")
    @Operation(summary = "Vote on a review", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewDTO> vote(
            @PathVariable Long id,
            @Valid @RequestBody VoteRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "anonymous") String customerId) {
        return ResponseEntity.ok(reviewService.voteOnReview(id, customerId, request.getHelpful()));
    }
}
