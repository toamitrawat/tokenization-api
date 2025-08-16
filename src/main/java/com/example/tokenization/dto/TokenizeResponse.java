package com.example.tokenization.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Observability: Stable response shape enables log/trace enrichment and APM dashboards.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenizeResponse {
    private String token;
}
