package com.ecommerce.reviewservice.exception;

import com.ecommerce.reviewservice.logging.LogThrottle;
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
import java.util.concurrent.TimeUnit;

/**
 * Error bodies carry fixed messages per error kind: no Java type names, parser text, SQL or
 * downstream bodies (S-34, S-35). Field validation messages are ours and stay.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // These lines can repeat at request rate (a bug on a hot path, a dead database or product-service under
    // load), so each distinct failure logs once per window and the rest is only counted (L3, L4).
    private static final LogThrottle ERROR_LOG = new LogThrottle(10, TimeUnit.SECONDS);
    private static final LogThrottle UNAVAILABLE_LOG = new LogThrottle(10, TimeUnit.SECONDS);

    @ExceptionHandler(ReviewNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ReviewNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /** product-service gave no answer (down, timed out, breaker open): an operating condition, not a bug. */
    @ExceptionHandler(ProductServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleProductServiceUnavailable(ProductServiceUnavailableException ex, HttpServletRequest request) {
        return unavailable(ex, request);
    }

    /** product-service answered with something unusable (a 5xx, an undecodable body). */
    @ExceptionHandler(ProductServiceErrorException.class)
    public ResponseEntity<ErrorResponse> handleProductServiceError(ProductServiceErrorException ex, HttpServletRequest request) {
        long suppressed = UNAVAILABLE_LOG.acquire("ProductServiceErrorException:" + ex.getUpstreamStatus());
        if (suppressed >= 0) {
            log.warn("product-service answered with status {} on {} {} (correlationId={}){}", ex.getUpstreamStatus(),
                request.getMethod(), request.getRequestURI(), MDC.get("correlationId"), LogThrottle.suppressedSuffix(suppressed));
        }
        return respond(HttpStatus.BAD_GATEWAY, "Upstream service error", request);
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
        Throwable dependency = DependencyFailures.unavailableCause(ex);
        if (dependency != null) {
            return unavailable(dependency, request);
        }
        long suppressed = ERROR_LOG.acquire(LogThrottle.keyOf(ex));
        if (suppressed >= 0) {
            log.error("Unhandled exception on {} {} (correlationId={}){}", request.getMethod(), request.getRequestURI(),
                MDC.get("correlationId"), LogThrottle.suppressedSuffix(suppressed), ex);
        }
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    /**
     * A dependency (the database, product-service) did not answer: 503 with a fixed message and one throttled
     * line without a stack trace (L4).
     */
    private static ResponseEntity<ErrorResponse> unavailable(Throwable cause, HttpServletRequest request) {
        long suppressed = UNAVAILABLE_LOG.acquire(cause.getClass().getName());
        if (suppressed >= 0) {
            log.warn("Dependency unavailable on {} {} (correlationId={}): {}{}", request.getMethod(), request.getRequestURI(),
                MDC.get("correlationId"), describe(cause), LogThrottle.suppressedSuffix(suppressed));
        }
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable", request);
    }

    /**
     * The failure and, when it differs, the deepest cause behind it ("Could not open JPA EntityManager for
     * transaction" says little; "Connection is not available, request timed out after 5000ms" or "Connection refused"
     * says what happened). Hibernate's own two lines per failed JDBC call are switched off, so this line has to
     * carry the reason.
     */
    private static String describe(Throwable cause) {
        Throwable root = cause;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 16; depth++) {
            root = root.getCause();
        }
        return root == cause ? brief(cause) : brief(cause) + "; root cause: " + brief(root);
    }

    /** Class and first line of the message, without control characters and capped, so nothing can forge a log line. */
    private static String brief(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage();
        int eol = message.indexOf('\n');
        String firstLine = (eol < 0 ? message : message.substring(0, eol)).replaceAll("\\p{Cntrl}", "_");
        return t.getClass().getName() + (firstLine.isEmpty() ? "" : ": " + firstLine.substring(0, Math.min(300, firstLine.length())));
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
            .timestamp(LocalDateTime.now()).status(status.value()).error(status.getReasonPhrase())
            .message(message).path(request.getRequestURI())
            .correlationId(MDC.get("correlationId")).build());
    }
}
