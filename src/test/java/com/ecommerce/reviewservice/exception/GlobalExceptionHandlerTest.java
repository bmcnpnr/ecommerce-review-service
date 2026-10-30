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
                .andExpect(jsonPath("$.message").value("Access denied"));
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
    void productServiceUnavailable_is503_withAFixedMessage() throws Exception {
        when(reviewService.createReview(any(), any()))
                .thenThrow(new com.ecommerce.reviewservice.exception.ProductServiceUnavailableException("Product service unavailable"));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice").header("X-Correlation-Id", "corr-503")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                .andExpect(jsonPath("$.correlationId").value("corr-503"));
    }

    @Test
    void productServiceAnsweringBadly_is502_withAFixedMessage() throws Exception {
        when(reviewService.createReview(any(), any()))
                .thenThrow(new com.ecommerce.reviewservice.exception.ProductServiceErrorException("product-service answered with status 500", 500));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Upstream service error"))
                .andExpect(content().string(not(containsString("500"))));
    }

    @Test
    void unknownProduct_is404() throws Exception {
        when(reviewService.createReview(any(), any()))
                .thenThrow(new com.ecommerce.reviewservice.exception.ProductNotFoundException("Product not found with id: 999"));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with id: 999"));
    }

    static java.util.stream.Stream<Throwable> databaseFailures() {
        return java.util.stream.Stream.of(
                new org.springframework.jdbc.CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                        new java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms")),
                new org.springframework.transaction.CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
                new org.springframework.dao.DataAccessResourceFailureException("connection refused: reviewdb:5432"),
                new org.springframework.dao.QueryTimeoutException("canceling statement due to statement timeout"),
                new org.springframework.dao.CannotAcquireLockException("deadlock detected"),
                new org.springframework.orm.jpa.JpaSystemException(new RuntimeException("wrapped", new java.sql.SQLTransientConnectionException("pool"))),
                // a killed backend / database restart: the failed rollback overrides the original exception
                new org.springframework.transaction.TransactionSystemException("Could not roll back JPA transaction",
                        new org.hibernate.TransactionException("Unable to rollback against JDBC Connection",
                                new java.sql.SQLException("This connection has been closed.", (String) null))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("databaseFailures")
    void databaseUnavailable_is503_withAFixedMessage_andNothingLeaks(Throwable failure) throws Exception {
        when(reviewService.getReviewById(anyLong())).thenThrow(failure);

        mockMvc.perform(get("/api/v1/reviews/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                .andExpect(content().string(not(containsString("Hikari"))))
                .andExpect(content().string(not(containsString("reviewdb"))));
    }

    @Test
    void aUniqueConstraintRace_staysA409() throws Exception {
        when(reviewService.createReview(any(), any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "dup", new java.sql.SQLException("duplicate key value violates unique constraint", "23505")));

        mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    // ───────────────────────── log volume (L3, L4) ─────────────────────────

    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    private ch.qos.logback.classic.Logger handlerLogger;

    @org.junit.jupiter.api.BeforeEach
    void captureHandlerLog() {
        handlerLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @org.junit.jupiter.api.AfterEach
    void releaseHandlerLog() {
        handlerLogger.detachAppender(logs);
        logs.stop();
    }

    /** Only for the flood test below, so its throttle key (the exception class) is not shared with other tests. */
    static final class FloodedPoolException extends org.springframework.dao.DataAccessResourceFailureException {
        FloodedPoolException() {
            super("pool exhausted");
        }
    }

    @Test
    void aDeadDatabaseUnderLoad_logsOneLineWithoutAStackTrace_notOneLinePerRequest() throws Exception {
        when(reviewService.getReviewById(anyLong())).thenThrow(new FloodedPoolException());

        for (int i = 0; i < 300; i++) {
            mockMvc.perform(get("/api/v1/reviews/" + (i + 1))).andExpect(status().isServiceUnavailable());
        }

        org.assertj.core.api.Assertions.assertThat(logs.list).as("300 failed requests, one log line").hasSize(1);
        ch.qos.logback.classic.spi.ILoggingEvent line = logs.list.get(0);
        org.assertj.core.api.Assertions.assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
        org.assertj.core.api.Assertions.assertThat(line.getThrowableProxy()).isNull();
        org.assertj.core.api.Assertions.assertThat(line.getFormattedMessage()).contains("Dependency unavailable").contains("FloodedPoolException");
    }

    /** Only for the flood test below, so its throttle key (the exception class) is not shared with other tests. */
    static final class FloodedProductServiceException extends ProductServiceUnavailableException {
        FloodedProductServiceException() {
            super("Product service unavailable");
        }
    }

    @Test
    void productServiceDownUnderLoad_logsOneLineWithoutAStackTrace_notOneLinePerRequest() throws Exception {
        when(reviewService.createReview(any(), any())).thenThrow(new FloodedProductServiceException());

        for (int i = 0; i < 300; i++) {
            mockMvc.perform(post("/api/v1/reviews").header("X-Username", "alice").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isServiceUnavailable());
        }

        org.assertj.core.api.Assertions.assertThat(logs.list).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(logs.list.get(0).getThrowableProxy()).isNull();
        org.assertj.core.api.Assertions.assertThat(logs.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
    }

    @Test
    void aBugOnAHotPath_logsOneStackTrace_notOneStackTracePerRequest() throws Exception {
        when(reviewService.getReviewById(anyLong())).thenAnswer(inv -> {
            throw new IllegalStateException("bug on the hot path");
        });

        for (int i = 0; i < 300; i++) {
            mockMvc.perform(get("/api/v1/reviews/" + (i + 1)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
        }

        org.assertj.core.api.Assertions.assertThat(logs.list).hasSize(1);
        ch.qos.logback.classic.spi.ILoggingEvent line = logs.list.get(0);
        org.assertj.core.api.Assertions.assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
        org.assertj.core.api.Assertions.assertThat(line.getThrowableProxy()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(line.getThrowableProxy().getMessage()).isEqualTo("bug on the hot path");
    }

    private static final String VALID_BODY =
            "{\"productId\":999,\"rating\":4,\"title\":\"Nice\",\"body\":\"Pretty good product overall\"}";
}
