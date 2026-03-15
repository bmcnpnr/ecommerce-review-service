package com.ecommerce.reviewservice.dto;

import com.ecommerce.reviewservice.model.ReviewStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
public class ReviewDTO {
    private Long id;
    private Long productId;
    private String customerId;
    private Short rating;
    private String title;
    private String body;
    private ReviewStatus status;
    private long helpfulVotes;
    private long unhelpfulVotes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
