# tokenization-api

Deterministic credit-card tokenization API built with Spring Boot. It generates a stable 16-digit token (always starting with `9`) for a given 16-digit card number and stores encrypted PAN data with AWS KMS-backed envelope encryption.

- Deterministic tokens: same PAN → same token (prefix `9`), with collision handling.
- Security: PAN is encrypted with AES-GCM; data keys are generated and protected by AWS KMS.
- Persistence: Oracle via Spring Data JPA; deterministic lookups by HMAC-SHA256 panHash.
- Robustness: Validation, structured JSON logging, and clear HTTP semantics.

Contents
- Overview
- Architecture
- API
- Configuration
- Build & Run
- Docker (example)
- Logging & Observability
- Error handling
- Security considerations
- Troubleshooting
- Next steps

## Overview

This service provides two endpoints:
- POST /api/tokenize: Accepts a 16-digit `ccNumber` and returns a token (`9` + 15 digits). If the PAN was tokenized before, the same token is returned.
- GET /api/detokenize: Accepts a token and returns the original `ccNumber`.

Determinism is achieved using an HMAC-SHA256-based panHash for lookups and token derivation. The PAN itself is never stored in clear text; it’s encrypted using an AES-256 data key generated via AWS KMS (envelope encryption). The encrypted data key and IV (nonce) are stored alongside the ciphertext.

## Architecture

- Spring Boot 3, Java 21
- Spring Web (REST), Spring Data JPA (Oracle, Hibernate 6)
- AWS SDK v2 (KMS)
- Validation (Jakarta), Logging (Logback + logstash JSON encoder)

Key components
- `TokenizationService`: Orchestrates deterministic tokenization, KMS data key generation/decryption, AES-GCM encrypt/decrypt, persistence, logging.
- `TokenDerivationService`: HMAC-SHA256 panHash + token derivation (tokens start with `9`).
- `CardToken` entity: Stores token, panHash, encrypted PAN, nonce, encrypted data key, collision counter.
- `TokenController`: REST API with request/response DTOs and appropriate status codes.
- `GlobalExceptionHandler`: Maps errors to JSON with HTTP status codes.
- `AwsKmsConfig`: Builds the KMS client; supports optional named AWS profile.

Data flow (tokenize)
1) Validate input (16 digits).
2) Compute HMAC panHash from PAN; lookup existing token by panHash.
3) If not found: KMS GenerateDataKey → AES-GCM encrypt PAN → generate deterministic token from panHash (+ collision counter if needed) → persist.
4) Return token with 201 Created.

Data flow (detokenize)
1) Lookup by token.
2) KMS Decrypt data key → AES-GCM decrypt PAN → return PAN with 200 OK.

## API

See docs/API.md for full details and examples.

Quick reference
- POST /api/tokenize
	- Request JSON: { "ccNumber": "1234567812345678" }
	- Response 201: { "token": "9xxxxxxxxxxxxxxx" }, Location: /api/detokenize?token=...
- GET /api/detokenize?token=9...
	- Response 200: { "ccNumber": "1234567812345678" }

PowerShell curl examples

```powershell
curl -X POST 'http://localhost:8088/api/tokenize' `
	-H 'Content-Type: application/json' `
	-d '{"ccNumber":"4111111111111111"}'

curl -X GET 'http://localhost:8088/api/detokenize?token=9XXXXXXXXXXXXXXXX'
```

Validation
- `ccNumber` must be exactly 16 digits; otherwise HTTP 400 with a field error map.

## Configuration

Main settings in `src/main/resources/application.yml`:

- Server
	- `server.port`: default 8088.
- Database (Oracle)
	- `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
	- `spring.jpa.hibernate.ddl-auto=none`
	- `spring.jpa.database-platform=org.hibernate.dialect.OracleDialect`
	- `spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.OracleDialect` (explicit safeguard)
- AWS KMS
	- `aws.kms.key-id`: required KeyId/ARN
	- `aws.region`: e.g., ap-south-1
	- `aws.profile` (optional): named profile for credentials
- Tokenization
	- `tokenization.hmacKeyBase64`: Base64-encoded HMAC key (keep secret; rotate per policy)
- Flyway
	- `spring.flyway.enabled=false` (migrations disabled by default)

Schema
- Initial DDL: `src/main/resources/db/migration/V1__create_card_tokens_table.sql`
- Entity indices on `TOKEN` and `PAN_HASH` for fast lookups.

More details in docs/CONFIGURATION.md

## Build & Run

Prereqs: Java 21, Maven, Oracle DB reachable, AWS credentials if KMS used.

Build
```powershell
mvn clean package -DskipTests
```

Run (executable JAR)
```powershell
java -jar target/tokenization-api-*.jar
```

Environment overrides
```powershell
$env:SERVER_PORT=8088
$env:AWS_REGION='ap-south-1'
$env:AWS_PROFILE='rolesanywhere'
java -jar target/tokenization-api-*.jar
```

## Docker (example)

Example Dockerfile (not included by default):

```dockerfile
FROM eclipse-temurin:21-jre
ARG JAR_FILE=target/tokenization-api-*.jar
COPY ${JAR_FILE} app.jar
ENV JAVA_OPTS=""
EXPOSE 8088
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app.jar"]
```

Build & run
```powershell
docker build -t tokenization-api .
docker run --rm -p 8088:8088 `
	-e AWS_REGION=ap-south-1 `
	-e AWS_PROFILE=rolesanywhere `
	-v "$HOME/.aws:/root/.aws:ro" `
	tokenization-api
```

Configure DB via env vars or a mounted `application.yml` volume as needed.

## Logging & Observability

- Structured JSON logs via Logback encoder for console and file output (`logs/tokenization-service.json`).
- Rotation: daily and at 10MB per file; 7 days retained.
- PII: Only the last 4 digits of PAN are ever logged; never log the full PAN.
- Correlation IDs: Add a servlet filter to populate MDC (e.g., `traceId`), automatically included in logs.

## Error handling

- Validation errors → 400 with `{ field: message }` body.
- Missing token → 404 `{ "error": "Token not found" }`.
- Internal failures → 500 `{ "error": "..." }`.
- Consider mapping DB connectivity to 503 (Service Unavailable) as a future enhancement.

## Security considerations

- Secrets: Keep `tokenization.hmacKeyBase64` confidential; rotate periodically.
- Keys: AWS KMS manages data keys; ensure IAM policies restrict access.
- Crypto: AES/GCM with unique 12-byte IV per encryption; ciphertext and encrypted data key are stored.
- Logging: Never log raw PAN; code logs last 4 only.
- Validation: Enforced 16-digit PAN input prevents malformed data.

## Troubleshooting

- Oracle dialect errors (Hibernate 6): Ensure `spring.jpa.database-platform` is `org.hibernate.dialect.OracleDialect` and add `spring.jpa.properties.hibernate.dialect` if needed.
- Windows file locks when packaging: Close running processes; retry `mvn clean package`. Avoid antivirus locks on `target/`.
- Logback JSON config issues: Use `LoggingEventCompositeJsonEncoder` with `<providers>` for console.
- KMS credential issues: Set `AWS_REGION` and, if needed, `AWS_PROFILE`; verify local AWS credentials.
- DB connectivity failures: Check JDBC URL, firewall/VPN, and credentials; consider fail-fast with health checks.

## Next steps

- Add OpenTelemetry tracing for KMS and JDBC.
- Extract AES-GCM operations into a dedicated component with focused unit tests.
- Integration tests for deterministic tokenization and error mapping.


# Tokenization Service (Java 21, Spring Boot 3.3.2, AWS SDK v2)

This sample demonstrates a tokenization service using AWS KMS (GenerateDataKey / Decrypt) and AES-GCM with envelope encryption.
It persists tokens and encrypted PANs in Oracle DB via JPA, and uses Flyway for schema management.

* Update `application.yml` with your Oracle DB credentials and AWS KMS Key ARN.
* Oracle JDBC driver (ojdbc11) may need to be added to your internal repository or local Maven cache.

Build:
```
mvn -U clean package
```

Run:
```
java -jar target/tokenization-service-0.0.1-SNAPSHOT.jar
```

Endpoints:
- POST /api/tokenize?pan=4111111111111111
- GET  /api/detokenize?token=...

Security:
- This sample simplifies error handling and security details. Before production, review HKDF key derivation, secure memory handling, AAD usage, logging, rate-limiting, and PCI controls.

## AWS credentials (including Roles Anywhere)

By default, the app uses the AWS default credential chain. You can also specify a named profile (e.g., Roles Anywhere) so the SDK loads credentials from your AWS config/credentials files.

application.yml:

```
aws:
	region: us-east-1
	profile: rolesanywhere
	kms:
		key-id: arn:aws:kms:us-east-1:123456789012:key/REPLACE_WITH_YOUR_KEY_ID

tokenization:
	# Base64-encoded 32-byte HMAC key
	hmacKeyBase64: ${TOKENIZATION_HMAC_KEY_BASE64:}
```

Alternatively, set via environment (PowerShell):

```
$env:AWS_PROFILE = "rolesanywhere"
$env:TOKENIZATION_HMAC_KEY_BASE64 = "<Base64-Encoded-32-Byte-Key>"
```

Notes for Roles Anywhere:
- Ensure your rolesanywhere profile uses a credential process/helper that fetches short-lived credentials.
- The profile must have KMS permissions: `kms:GenerateDataKey`, `kms:Decrypt` on your key.
