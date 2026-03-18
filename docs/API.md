# API Reference

## Base URLs

| Environment | URL |
|---|---|
| Local JAR / Docker Compose | `http://localhost:8088` |
| Docker Desktop Kubernetes (ingress) | `http://tokenization-api.local` |
| Kubernetes port-forward | `http://localhost:8088` |

## Required Headers

Every request to `/api/*` must include:

| Header | Description |
|---|---|
| `source` | Caller service identifier (e.g., `payments-service`) |
| `correlationId` | Request trace ID for log correlation |

Missing or blank headers return HTTP 400.

## Conventions

- **Deterministic**: Same PAN always yields the same token. Tokens always start with `9` and are exactly 16 digits.
- **Idempotent tokenize**: Repeated requests with the same `ccNumber` return the same token — safe to call multiple times.
- **Validation errors**: HTTP 400 with `{ "fieldName": "message" }` body.
- **Other errors**: `{ "error": "..." }` with appropriate status code.
- **201 Created** includes a `Location` header pointing to the detokenize endpoint.

---

## Endpoints

### POST /api/tokenize

Tokenizes a 16-digit credit card number.

**Request**

```
POST /api/tokenize
Content-Type: application/json
source: my-service
correlationId: req-abc-001
```

```json
{
  "ccNumber": "4111111111111111"
}
```

**Constraints**
- `ccNumber`: string, exactly 16 digits (regex `^\d{16}$`)

**Responses**

`201 Created`
```
Location: /api/detokenize?token=9142960579605051
```
```json
{
  "token": "9142960579605051"
}
```

`400 Bad Request` (validation)
```json
{
  "ccNumber": "ccNumber must be exactly 16 digits"
}
```

`400 Bad Request` (missing header)
```json
{
  "source": "source header must not be blank"
}
```

`500 Internal Server Error`
```json
{
  "error": "Tokenization failed"
}
```

---

### GET /api/detokenize

Detokenizes a token back to the original PAN.

**Request**

```
GET /api/detokenize?token=9142960579605051
source: my-service
correlationId: req-abc-002
```

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `token` | string | yes | 16-digit token beginning with `9` |

**Responses**

`200 OK`
```json
{
  "ccNumber": "4111111111111111"
}
```

`404 Not Found`
```json
{
  "error": "Token not found"
}
```

`500 Internal Server Error`
```json
{
  "error": "Detokenization failed"
}
```

---

## Health Endpoints

Spring Actuator probes — no headers required.

```
GET /actuator/health/liveness
→ {"status":"UP"}
```

```
GET /actuator/health/readiness
→ {"status":"UP"}   (only when Oracle is reachable)
```

---

## Full Examples

**Git Bash / Linux**

```bash
# Tokenize
curl -X POST http://tokenization-api.local/api/tokenize \
  -H "Content-Type: application/json" \
  -H "source: test" \
  -H "correlationId: test-001" \
  -d '{"ccNumber":"4111111111111111"}'

# Detokenize
curl "http://tokenization-api.local/api/detokenize?token=9142960579605051" \
  -H "source: test" \
  -H "correlationId: test-002"
```

**PowerShell**

```powershell
# Tokenize
curl -X POST 'http://tokenization-api.local/api/tokenize' `
  -H 'Content-Type: application/json' `
  -H 'source: test' `
  -H 'correlationId: test-001' `
  -d '{"ccNumber":"4111111111111111"}'

# Detokenize
curl 'http://tokenization-api.local/api/detokenize?token=9142960579605051' `
  -H 'source: test' `
  -H 'correlationId: test-002'
```

---

## Error Model

| Code | Scenario | Body format |
|---|---|---|
| 400 | Validation failure | `{ "fieldName": "message" }` |
| 404 | Token not found | `{ "error": "Token not found" }` |
| 500 | Internal error | `{ "error": "..." }` |

## Rate Limits

Not enforced by the service. Enforce at the ingress/gateway level. Lookups are indexed on `TOKEN` and `PAN_HASH` for fast response.
