package com.ecommerce.reviewservice.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data @Builder
public class ReviewSummaryDTO {
    private Long productId;
    private double averageRating;
    private long totalReviews;
    private long approvedReviews;
    private Map<Integer, Long> ratingDistribution;
}
