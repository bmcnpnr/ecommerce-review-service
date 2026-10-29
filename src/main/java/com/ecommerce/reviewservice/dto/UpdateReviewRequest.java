package com.ecommerce.reviewservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UpdateReviewRequest {

    @Min(1) @Max(5)
    private Short rating;

    @Size(min = 3, max = 255)
    @Pattern(regexp = "^[^\\u0000]*$", message = "Must not contain NUL characters")
    private String title;

    @Size(min = 10, max = 2000)
    @Pattern(regexp = "^[^\\u0000]*$", message = "Must not contain NUL characters")
    private String body;
}
