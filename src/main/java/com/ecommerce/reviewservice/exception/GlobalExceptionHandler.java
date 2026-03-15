package com.ecommerce.reviewservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.LocalDateTime;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ReviewNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ReviewNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(404).error("Not Found")
            .message(ex.getMessage()).path(request.getRequestURI())
            .correlationId(MDC.get("correlationId")).build());
    }

    @ExceptionHandler(DuplicateReviewException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateReviewException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(409).error("Conflict")
            .message(ex.getMessage()).path(request.getRequestURI())
            .correlationId(MDC.get("correlationId")).build());
    }

    @ExceptionHandler(UnauthorizedReviewAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedReviewAccessException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(403).error("Forbidden")
            .message(ex.getMessage()).path(request.getRequestURI())
            .correlationId(MDC.get("correlationId")).build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(400).error("Validation Failed")
            .message("Request validation failed").path(request.getRequestURI())
            .correlationId(MDC.get("correlationId")).fieldErrors(fieldErrors).build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(500).error("Internal Server Error")
            .message("An unexpected error occurred").path(request.getRequestURI())
            .correlationId(MDC.get("correlationId")).build());
    }
}
