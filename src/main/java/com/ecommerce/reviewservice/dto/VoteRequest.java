package com.ecommerce.reviewservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VoteRequest {
    @NotNull
    private Boolean helpful;
}
