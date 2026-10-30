package com.ecommerce.reviewservice.repository;

import com.ecommerce.reviewservice.model.ReviewVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewVoteRepository extends JpaRepository<ReviewVote, Long> {
    Optional<ReviewVote> findByReviewIdAndCustomerId(Long reviewId, String customerId);

    /**
     * Helpful and unhelpful votes per review in one query. Counting each review's votes through the lazy
     * collection cost one query per review (and loaded every vote row) on every list request.
     */
    @Query("select v.review.id as reviewId, v.helpful as helpful, count(v) as total from ReviewVote v "
            + "where v.review.id in :reviewIds group by v.review.id, v.helpful")
    List<VoteCount> countByReview(@Param("reviewIds") Collection<Long> reviewIds);

    interface VoteCount {
        Long getReviewId();

        Boolean getHelpful();

        long getTotal();
    }
}
