package com.ecommerce.reviewservice.exception;

import com.ecommerce.reviewservice.client.ProductServiceClient;
import com.ecommerce.reviewservice.controller.ReviewController;
import com.ecommerce.reviewservice.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Every branch of {@link GlobalExceptionHandler}: status, error body shape and the
 * correlation id stamped by {@code CorrelationIdFilter}.
 */
@WebMvcTest(controllers = ReviewController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    @MockitoBean
    private ProductServiceClient productServiceClient;

    @Test
    void reviewNotFound_is404_withFullBody() throws Exception {
        when(reviewService.getReviewById(5L)).thenThrow(new ReviewNotFoundException("Review not found with id: 5"));

        mockMvc.perform(get("/api/v1/reviews/5").header("X-Correlation-Id", "corr-5"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "corr-5"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Review not found with id: 5"))
                .andExpect(jsonPath("$.path").value("/api/v1/reviews/5"))
                .andExpect(jsonPath("$.correlationId").value("corr-5"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void duplicateReview_is409() throws Exception {
        when(reviewService.createReview(any(), eq("alice"))).thenThrow(new DuplicateReviewException("dup"));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"rating\":4,\"title\":\"Nice\",\"body\":\"Pretty good product overall\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("dup"));
    }

    @Test
    void unauthorizedAccess_is403() throws Exception {
        when(reviewService.updateReview(eq(1L), any(), eq("eve"))).thenThrow(new UnauthorizedReviewAccessException("nope"));

        mockMvc.perform(put("/api/v1/reviews/1").header("X-Username", "eve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("nope"));
    }

    @Test
    void validation_is400_withFieldErrorsAndMessage() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(4))
                .andExpect(jsonPath("$.fieldErrors[0].field").exists())
                .andExpect(jsonPath("$.fieldErrors[0].message").exists());
    }

    @Test
    void malformedJson_is400() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content("{\"rating\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void typeMismatch_is400() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/eleven"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void methodNotAllowed_is405() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/product/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unexpected_is500_withGenericMessage() throws Exception {
        when(reviewService.getReviewById(anyLong())).thenThrow(new IllegalStateException("connection string secret"));

        mockMvc.perform(get("/api/v1/reviews/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void productLookupFailure_surfacesAs500Today() throws Exception {
        // ReviewService wraps every product-service failure in a bare RuntimeException,
        // which only the catch-all handles (ReviewApiIT covers the 404 -> 4xx path).
        when(reviewService.createReview(any(), any()))
                .thenThrow(new RuntimeException("Product 999 not found or product service unavailable"));

        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":999,\"rating\":4,\"title\":\"Nice\",\"body\":\"Pretty good product overall\"}"))
                .andExpect(status().isInternalServerError());
    }
}
