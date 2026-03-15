package com.ecommerce.reviewservice.service;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.dto.*;
import com.ecommerce.reviewservice.exception.*;
import com.ecommerce.reviewservice.model.*;
import com.ecommerce.reviewservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock ReviewRepository reviewRepository;
    @Mock ReviewVoteRepository reviewVoteRepository;
    @Mock ProductServiceClient productServiceClient;
    @InjectMocks ReviewService reviewService;

    private ProductDTO productDTO;

    @BeforeEach
    void setup() {
        productDTO = new ProductDTO();
        productDTO.setId(1L);
        productDTO.setSku("SKU-001");
        productDTO.setName("Test Product");
        productDTO.setPrice(BigDecimal.valueOf(99.99));
    }

    @Test
    void createReview_success() {
        when(productServiceClient.getProductById(1L)).thenReturn(productDTO);
        when(reviewRepository.findByProductIdAndCustomerId(1L, "user1")).thenReturn(Optional.empty());

        Review saved = Review.builder().id(1L).productId(1L).customerId("user1")
            .rating((short)5).title("Great").body("Really great product").status(ReviewStatus.PENDING).build();
        when(reviewRepository.save(any())).thenReturn(saved);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setProductId(1L);
        request.setRating((short)5);
        request.setTitle("Great");
        request.setBody("Really great product");

        ReviewDTO result = reviewService.createReview(request, "user1");
        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getRating()).isEqualTo((short)5);
    }

    @Test
    void createReview_duplicateThrows() {
        when(productServiceClient.getProductById(1L)).thenReturn(productDTO);
        Review existing = Review.builder().id(1L).productId(1L).customerId("user1").build();
        when(reviewRepository.findByProductIdAndCustomerId(1L, "user1")).thenReturn(Optional.of(existing));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setProductId(1L);
        request.setRating((short)4);
        request.setTitle("Good");
        request.setBody("Pretty good product overall");

        assertThatThrownBy(() -> reviewService.createReview(request, "user1"))
            .isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    void deleteReview_byNonOwnerNonAdmin_throws() {
        Review review = Review.builder().id(1L).customerId("owner").build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.deleteReview(1L, "other-user", "CUSTOMER"))
            .isInstanceOf(UnauthorizedReviewAccessException.class);
    }

    @Test
    void deleteReview_byAdmin_success() {
        Review review = Review.builder().id(1L).customerId("owner").build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

        assertThatCode(() -> reviewService.deleteReview(1L, "admin-user", "ADMIN"))
            .doesNotThrowAnyException();
        verify(reviewRepository).delete(review);
    }

    @Test
    void getReviewById_notFound_throws() {
        when(reviewRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reviewService.getReviewById(99L))
            .isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void getProductReviewSummary_noReviews_returnsZero() {
        when(reviewRepository.findByProductIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(List.of());
        when(reviewRepository.findByProductId(1L)).thenReturn(List.of());

        ReviewSummaryDTO summary = reviewService.getProductReviewSummary(1L);
        assertThat(summary.getAverageRating()).isEqualTo(0.0);
        assertThat(summary.getTotalReviews()).isEqualTo(0);
    }
}
