package com.ecommerce.reviewservice.repository;

import com.ecommerce.reviewservice.model.Review;
import com.ecommerce.reviewservice.model.ReviewStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
    long countByProductId(Long productId);

    // The list endpoints are public and a popular product has any number of reviews: only ever one page.
    List<Review> findByProductId(Long productId, Pageable pageable);
    List<Review> findByProductIdAndStatus(Long productId, ReviewStatus status, Pageable pageable);
    List<Review> findByCustomerId(String customerId, Pageable pageable);
    List<Review> findByCustomerIdAndStatus(String customerId, ReviewStatus status, Pageable pageable);

    /** Number of reviews per rating, computed by the database: the summary never loads the reviews themselves. */
    @Query("select r.rating as rating, count(r) as total from Review r "
            + "where r.productId = :productId and r.status = :status group by r.rating")
    List<RatingCount> countRatings(@Param("productId") Long productId, @Param("status") ReviewStatus status);

    interface RatingCount {
        Short getRating();

        long getTotal();
    }
}
