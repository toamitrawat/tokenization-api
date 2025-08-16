package com.example.tokenization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TokenizeRequest {

    @JsonProperty("ccNumber")
    @Pattern(regexp = "^\\d{16}$", message = "ccNumber must be exactly 16 digits")
    private String ccNumber;

    // Observability: Validation ensures bad inputs are consistently rejected with 400,
    // simplifying API error rate monitoring and reducing noisy server-side failures.

}
