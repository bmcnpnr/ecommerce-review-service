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

    // Spring MVC raises these for malformed client requests. Without explicit
    // handlers they fall through to the Exception catch-all below and are
    // reported as 500, hiding the fact that the caller sent something invalid.
    @ExceptionHandler({
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleClientError(Exception ex, HttpServletRequest httpRequest) {
        HttpStatus status = (ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException)
                ? HttpStatus.METHOD_NOT_ALLOWED
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(status.value()).error(status.getReasonPhrase())
            .message(ex.getMessage()).path(httpRequest.getRequestURI())
            .correlationId(MDC.get("correlationId")).build());
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
