package com.example.tokenization.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TokenizeRequest {

    @Pattern(regexp = "^\\d{16}$", message = "ccNumber must be exactly 16 digits")
    private String ccNumber;
}
