# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Build with tests
mvn clean verify

# Run locally
java -jar target/tokenization-service-0.0.1-SNAPSHOT.jar

# Run a single test class
mvn test -Dtest=TokenizationServiceTest

# Run a single test method
mvn test -Dtest=TokenizationServiceTest#testTokenizeNewCard

# Docker (requires external kafka-net network)
docker compose up -d --build
docker compose logs -f app
docker compose down -v
```

## Architecture Overview

**Purpose**: Deterministic credit card tokenization with AWS KMS-backed envelope encryption.

**Core invariant**: The same PAN always produces the same 16-digit token (prefix `9`) — determinism is achieved via HMAC-SHA256 of the PAN, not random generation. Token collisions are resolved via a counter stored in the DB.

**Encryption model** (envelope encryption):
1. On tokenize: KMS generates an AES-256 data key → PAN encrypted with AES-256-GCM → encrypted data key and ciphertext persisted
2. On detokenize: encrypted data key decrypted by KMS (with Caffeine cache to reduce KMS calls) → PAN decrypted → plaintext key zeroed from memory

**Request flow**:
- `POST /api/tokenize` → `TokenController` → `TokenizationService` (HMAC lookup → KMS → encrypt → persist) → 201 + Location
- `GET /api/detokenize?token=...` → `TokenController` → `TokenizationService` (DB lookup → KMS decrypt → AES decrypt) → 200 + PAN

**Key components**:
| Package | Responsibility |
|---|---|
| `controller` | REST layer; populates MDC (`source`, `correlationId`) from headers |
| `service` | Orchestrates tokenize/detokenize; `@Transactional`; logs masked PAN only |
| `kms` | `KmsDataKeyService` wraps AWS KMS; `DataKeyCache` (Caffeine, TTL 30s, max 100) |
| `crypto` | `TokenDerivationService` — HMAC-SHA256 → 16-digit token mapping |
| `entity` | `CardToken` JPA entity → `CARD_TOKENS` table (Oracle) |
| `repository` | `CardTokenRepository` — `findByToken`, `findByPanHash` |
| `config` | `AwsKmsConfig` — builds `KmsClient` bean; supports AWS Roles Anywhere profile |
| `exception` | `GlobalExceptionHandler` (`@RestControllerAdvice`); 400/404/500 mappings |

## Infrastructure Dependencies

| Dependency | Details |
|---|---|
| Oracle DB | localhost:1521/XEPDB1 (local) / oracle-xe:1521 (Docker), user: `amit` |
| AWS KMS | Region: `ap-south-1`; CMK ARN in `application.yml`; needs `kms:GenerateDataKey` + `kms:Decrypt` |
| AWS Auth | AWS Roles Anywhere (certs in `/aws-ra/`) or default credential chain |

## Configuration

All tunable values are in `src/main/resources/application.yml`. Critical properties:

```yaml
aws.kms.key-id:          # KMS CMK ARN
aws.region:              # ap-south-1
tokenization.hmacKeyBase64:          # Base64 HMAC signing key — rotate per policy
tokenization.kms.cache.ttlSeconds:   # Data key cache TTL (default: 30)
tokenization.kms.cache.maxSize:      # Cache max entries (default: 100)
```

All properties can be overridden via environment variables (`SPRING_DATASOURCE_URL`, `AWS_KMS_KEY_ID`, etc.).

## Database Schema

Table: `CARD_TOKENS` — managed via Flyway (disabled by default, `ddl-auto: none`).
Migration script: `src/main/resources/db/migration/V1__create_card_tokens_table.sql`.

Key columns: `TOKEN` (unique), `PAN_HASH` (unique, HMAC for lookup), `ENCRYPTED_PAN` (BLOB), `NONCE` (RAW 12), `ENCRYPTED_DATA_KEY` (BLOB), `COLLISION_COUNTER`.

## Security Notes

- **No full PAN ever logged** — service logs last 4 digits only
- Plaintext data keys are zeroed via `Arrays.fill()` after use (`DataKeyPair.clearPlainDataKey()`)
- HMAC key and DB credentials must be supplied via environment variables in production — never commit real values
- `correlationId` and `source` request headers are required by the API (validated at controller level)
