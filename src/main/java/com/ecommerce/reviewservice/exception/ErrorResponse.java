package com.ecommerce.reviewservice.exception;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private String correlationId;
    private List<FieldError> fieldErrors;

    public record FieldError(String field, String message) {}
}
