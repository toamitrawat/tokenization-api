# API Reference

Base URL: http://localhost:8088

Content-Type: application/json unless noted.
Authentication: none (local/dev). Add auth at the gateway or via filters if needed.

## Conventions
- Deterministic behavior: Same PAN yields the same token. Tokens always start with `9` and are 16 digits.
- Validation: Requests failing validation return HTTP 400 with a field-to-message map.
- Errors: Non-validation errors return `{ "error": "..." }` with an appropriate status code.
- Headers: POST returns 201 Created with a Location header pointing to the detokenize endpoint.

## Endpoints

### POST /api/tokenize
Tokenizes a 16-digit credit card number.

Request body
```json
{
  "ccNumber": "4111111111111111"
}
```

Constraints
- `ccNumber`: string, exactly 16 digits (regex `^\d{16}$`).

Responses
- 201 Created
  - Headers: `Location: /api/detokenize?token=9xxxxxxxxxxxxxxx`
  - Body:
    ```json
    {
      "token": "9xxxxxxxxxxxxxxx"
    }
    ```
- 400 Bad Request (validation)
  ```json
  {
    "ccNumber": "ccNumber must be exactly 16 digits"
  }
  ```
- 500 Internal Server Error
  ```json
  { "error": "Tokenization failed" }
  ```

Notes
- Idempotent for the same `ccNumber`: returns the same token if already tokenized.
- Logs only include last 4 digits; full PANs are never logged or returned.

### GET /api/detokenize
Detokenizes a token back to the original PAN.

Query params
- `token` (string, required): 16-digit token beginning with `9`.

Example
```
GET /api/detokenize?token=9xxxxxxxxxxxxxxx
```

Responses
- 200 OK
  ```json
  { "ccNumber": "4111111111111111" }
  ```
- 404 Not Found
  ```json
  { "error": "Token not found" }
  ```
- 500 Internal Server Error
  ```json
  { "error": "Detokenization failed" }
  ```

## Error model
- Validation errors: HTTP 400 with `{ field: message }`.
- Other errors: `{ "error": "..." }` with appropriate status code.

## Rate limits & performance
- Not enforced by service. Consider enforcement at gateway/load balancer.
- Lookups are indexed by `token` and `panHash`.

## Idempotency
- Tokenize is deterministic; repeated requests with the same `ccNumber` return the same token.
