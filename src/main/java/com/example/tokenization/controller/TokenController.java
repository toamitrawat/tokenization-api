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

@RestController
@RequestMapping("/api")
@Validated
@RequiredArgsConstructor
public class TokenController {

    private final TokenizationService service;

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
