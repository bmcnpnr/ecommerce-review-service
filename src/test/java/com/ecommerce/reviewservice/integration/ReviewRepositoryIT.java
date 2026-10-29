package com.ecommerce.reviewservice.integration;

import com.ecommerce.reviewservice.model.Review;
import com.ecommerce.reviewservice.model.ReviewStatus;
import com.ecommerce.reviewservice.model.ReviewVote;
import com.ecommerce.reviewservice.repository.ReviewRepository;
import com.ecommerce.reviewservice.repository.ReviewVoteRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repositories against PostgreSQL with the production Flyway schema. Each test
 * runs in a transaction that @DataJpaTest rolls back.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReviewRepositoryIT extends PostgresIntegrationTestSupport {

    @Autowired private ReviewRepository reviewRepository;
    @Autowired private ReviewVoteRepository voteRepository;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // Other IT classes share the container and commit data; this is rolled back with the test.
        jdbc.update("DELETE FROM review_votes");
        jdbc.update("DELETE FROM reviews");
    }

    @Test
    void flywayMigrationsWereApplied() {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2");
    }

    @Test
    void oneReviewPerCustomerAndProduct() {
        reviewRepository.saveAndFlush(review(1L, "alice", 5, ReviewStatus.PENDING));
        reviewRepository.saveAndFlush(review(2L, "alice", 4, ReviewStatus.PENDING));   // other product: fine
        reviewRepository.saveAndFlush(review(1L, "bob", 4, ReviewStatus.PENDING));     // other customer: fine

        assertThatThrownBy(() -> reviewRepository.saveAndFlush(review(1L, "alice", 1, ReviewStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ratingOutsideOneToFive_isRejectedByCheckConstraint() {
        // The entity has no range constraint; only the migration's CHECK protects the column.
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(review(1L, "alice", 6, ReviewStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ratingZero_isRejectedByCheckConstraint() {
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(review(1L, "alice", 0, ReviewStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneVotePerCustomerAndReview() {
        Review r = reviewRepository.saveAndFlush(review(1L, "alice", 5, ReviewStatus.APPROVED));
        voteRepository.saveAndFlush(vote(r, "bob", true));
        voteRepository.saveAndFlush(vote(r, "carol", false));

        assertThatThrownBy(() -> voteRepository.saveAndFlush(vote(r, "bob", false)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findVoteByReviewAndCustomer() {
        Review r = reviewRepository.saveAndFlush(review(1L, "alice", 5, ReviewStatus.APPROVED));
        voteRepository.saveAndFlush(vote(r, "bob", true));

        assertThat(voteRepository.findByReviewIdAndCustomerId(r.getId(), "bob")).get()
                .extracting(ReviewVote::getHelpful).isEqualTo(true);
        assertThat(voteRepository.findByReviewIdAndCustomerId(r.getId(), "carol")).isEmpty();
    }

    @Test
    void reviewVotesCollection_isLoadedFromTheVoteTable() {
        Review r = reviewRepository.saveAndFlush(review(1L, "alice", 5, ReviewStatus.APPROVED));
        voteRepository.saveAllAndFlush(List.of(vote(r, "bob", true), vote(r, "carol", true), vote(r, "dave", false)));
        em.clear();

        Review loaded = reviewRepository.findById(r.getId()).orElseThrow();
        assertThat(loaded.getVotes()).hasSize(3);
        assertThat(loaded.getVotes()).filteredOn(ReviewVote::getHelpful).hasSize(2);
    }

    @Test
    void deletingReview_deletesItsVotes() {
        Review r = reviewRepository.saveAndFlush(review(1L, "alice", 5, ReviewStatus.APPROVED));
        voteRepository.saveAllAndFlush(List.of(vote(r, "bob", true), vote(r, "carol", false)));
        em.clear();

        reviewRepository.delete(reviewRepository.findById(r.getId()).orElseThrow());
        reviewRepository.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_votes", Long.class)).isZero();
    }

    @Test
    void deletingReviewRowDirectly_cascadesInTheDatabase() {
        Review r = reviewRepository.saveAndFlush(review(1L, "alice", 5, ReviewStatus.APPROVED));
        voteRepository.saveAndFlush(vote(r, "bob", true));

        jdbc.update("DELETE FROM reviews WHERE id = ?", r.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_votes", Long.class)).isZero();
    }

    @Test
    void finderAndAggregateQueries() {
        reviewRepository.saveAll(List.of(
                review(1L, "a", 5, ReviewStatus.APPROVED),
                review(1L, "b", 3, ReviewStatus.APPROVED),
                review(1L, "c", 1, ReviewStatus.PENDING),
                review(1L, "d", 2, ReviewStatus.REJECTED),
                review(2L, "a", 4, ReviewStatus.APPROVED)));
        reviewRepository.flush();

        assertThat(reviewRepository.findByProductId(1L)).hasSize(4);
        assertThat(reviewRepository.findByProductIdAndStatus(1L, ReviewStatus.APPROVED))
                .extracting(Review::getCustomerId).containsExactlyInAnyOrder("a", "b");
        assertThat(reviewRepository.countByProductIdAndStatus(1L, ReviewStatus.APPROVED)).isEqualTo(2);
        assertThat(reviewRepository.countByProductIdAndStatus(1L, ReviewStatus.PENDING)).isEqualTo(1);
        assertThat(reviewRepository.countByProductIdAndStatus(3L, ReviewStatus.APPROVED)).isZero();
        assertThat(reviewRepository.findByCustomerId("a")).extracting(Review::getProductId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(reviewRepository.findByProductIdAndCustomerId(1L, "c")).get()
                .extracting(Review::getStatus).isEqualTo(ReviewStatus.PENDING);
        assertThat(reviewRepository.findByProductIdAndCustomerId(2L, "b")).isEmpty();
    }

    @Test
    void longBodyAndMaxLengthTitle_roundTrip() {
        Review r = review(1L, "alice", 5, ReviewStatus.PENDING);
        r.setTitle("t".repeat(255));
        r.setBody("b".repeat(2000));
        Long id = reviewRepository.saveAndFlush(r).getId();
        em.clear();

        Review loaded = reviewRepository.findById(id).orElseThrow();
        assertThat(loaded.getTitle()).hasSize(255);
        assertThat(loaded.getBody()).hasSize(2000);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    private static Review review(long productId, String customer, int rating, ReviewStatus status) {
        return Review.builder().productId(productId).customerId(customer).rating((short) rating)
                .title("Title " + customer).body("Body text by " + customer).status(status).build();
    }

    private static ReviewVote vote(Review review, String customer, boolean helpful) {
        return ReviewVote.builder().review(review).customerId(customer).helpful(helpful).build();
    }
}
