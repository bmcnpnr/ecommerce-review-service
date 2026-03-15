package com.ecommerce.reviewservice.repository;

import com.ecommerce.reviewservice.model.Review;
import com.ecommerce.reviewservice.model.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByProductId(Long productId);
    List<Review> findByCustomerId(String customerId);
    Optional<Review> findByProductIdAndCustomerId(Long productId, String customerId);
    List<Review> findByProductIdAndStatus(Long productId, ReviewStatus status);
    long countByProductIdAndStatus(Long productId, ReviewStatus status);
}
