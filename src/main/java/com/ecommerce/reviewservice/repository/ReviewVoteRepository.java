package com.ecommerce.reviewservice.repository;

import com.ecommerce.reviewservice.model.ReviewVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ReviewVoteRepository extends JpaRepository<ReviewVote, Long> {
    Optional<ReviewVote> findByReviewIdAndCustomerId(Long reviewId, String customerId);
}
