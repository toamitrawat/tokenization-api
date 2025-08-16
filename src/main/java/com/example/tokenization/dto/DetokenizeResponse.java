package com.example.tokenization.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Observability: Keep response thin and consistent; avoid echoing sensitive metadata.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DetokenizeResponse {
    private String ccNumber;
}
