package com.ecommerce.reviewservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateReviewRequest {

    @NotNull
    private Long productId;

    @NotNull
    @Min(1) @Max(5)
    private Short rating;

    @NotBlank
    @Size(min = 3, max = 255)
    private String title;

    @NotBlank
    @Size(min = 10, max = 2000)
    private String body;
}
