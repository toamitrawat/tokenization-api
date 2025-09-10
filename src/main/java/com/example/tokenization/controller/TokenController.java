package com.example.tokenization.controller;

import com.example.tokenization.dto.DetokenizeResponse;
import com.example.tokenization.dto.TokenizeRequest;
import com.example.tokenization.dto.TokenizeResponse;
import com.example.tokenization.service.TokenizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.MDC;

/**
 * REST controller exposing tokenization endpoints.
 *
 * Observability notes:
 * - We return typed JSON DTOs (request/response) for easier parsing in logs and APMs.
 * - The service logs are structured (JSON via Logback) and avoid printing full PANs; only last 4 digits are logged.
 * - Add a per-request correlation ID (e.g., MDC key "traceId") via a filter/interceptor if you need to stitch logs across services.
 */
@RestController
@RequestMapping("/api")
@Validated
@RequiredArgsConstructor
public class TokenController {

    private final TokenizationService service;

    /**
     * Tokenizes a 16-digit credit card number and returns a deterministic token.
     *
     * Observability: The underlying service logs the operation with masked PAN (last 4 only)
     * and structured fields. On success, we respond 201 Created with Location header.
     */
    @PostMapping("/tokenize")
    public ResponseEntity<TokenizeResponse> tokenize(
            @RequestHeader("source") @NotBlank(message = "source header must not be blank") String source,
            @RequestHeader("correlationId") @NotBlank(message = "correlationId header must not be blank") String correlationId,
            @RequestBody @Valid TokenizeRequest request) {
        MDC.put("source", source);
        MDC.put("correlationId", correlationId);
        try {
            String token = service.tokenize(request.getCcNumber());
            HttpHeaders headers = new HttpHeaders();
            headers.add("source", source);
            headers.add("correlationId", correlationId);
            return ResponseEntity
                    .created(java.net.URI.create("/api/detokenize?token=" + token))
                    .headers(headers)
                    .body(new TokenizeResponse(token));
        } finally {
            MDC.remove("source");
            MDC.remove("correlationId");
        }
    }

    /**
     * Resolves a token back to the original CCnumber.
     *
     * Observability: We treat missing tokens as 404 (via a mapped exception). Successful calls
     * are logged with masked CCnumber (last 4). Consider adding Micrometer timers on controller or service
     * methods to observe latency and error rates.
     */
    @GetMapping("/detokenize")
    public ResponseEntity<DetokenizeResponse> detokenize(
            @RequestHeader("source") @NotBlank(message = "source header must not be blank") String source,
            @RequestHeader("correlationId") @NotBlank(message = "correlationId header must not be blank") String correlationId,
            @RequestParam(name = "token") @NotBlank(message = "Token must not be blank") String token) {
        MDC.put("source", source);
        MDC.put("correlationId", correlationId);
        try {
            String cc = service.detokenize(token);
            HttpHeaders headers = new HttpHeaders();
            headers.add("source", source);
            headers.add("correlationId", correlationId);
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new DetokenizeResponse(cc));
        } finally {
            MDC.remove("source");
            MDC.remove("correlationId");
        }
    }
}
