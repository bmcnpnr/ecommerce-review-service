package com.ecommerce.reviewservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Error bodies carry fixed messages per error kind: no Java type names, parser text, SQL or
 * downstream bodies (S-34, S-35). Field validation messages are ours and stay.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ReviewNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ReviewNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateReviewException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateReviewException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        // a unique-constraint race, for example two concurrent reviews of one product by one customer
        return respond(HttpStatus.CONFLICT, "Resource already exists or violates a constraint", request);
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthenticated(UnauthenticatedException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    @ExceptionHandler(UnauthorizedReviewAccessException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(UnauthorizedReviewAccessException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'", request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, ServletRequestBindingException.class})
    public ResponseEntity<ErrorResponse> handleBinding(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Missing or invalid request parameter", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_ACCEPTABLE, "Not acceptable", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(status.value()).error(status.getReasonPhrase())
            .message(message).path(request.getRequestURI())
            .correlationId(MDC.get("correlationId")).build());
    }
}
