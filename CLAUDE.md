# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Build with tests
./mvnw clean verify

# Run locally
java -jar target/tokenization-service-0.0.1-SNAPSHOT.jar

# Run a single test class
./mvnw test -Dtest=TokenizationServiceTest

# Run a single test method
./mvnw test -Dtest=TokenizationServiceTest#testTokenizeNewCard

# Docker Compose (requires Oracle XE running)
docker compose up -d --build
docker compose logs -f app
docker compose down -v
```

## Kubernetes / Helm Commands

```bash
# One-time cluster bootstrap (nginx ingress, namespace, secrets)
# Run from Git Bash — requires REGISTRY_USER, REGISTRY_PASS, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
bash k8s/bootstrap.sh

# Start Jenkins + inject kubeconfig + install kubectl/helm/docker CLI
bash k8s/jenkins-setup.sh

# Manual Helm deploy (bypassing Jenkins)
helm upgrade --install tokenization-api helm/tokenization-api \
  --namespace tokenization \
  --set image.tag=<BUILD_NUMBER> \
  --set secrets.dbUsername=amit \
  --set secrets.dbPassword=<password> \
  --set secrets.hmacKeyBase64=<key>

# Watch pod startup
kubectl get pods -n tokenization -w

# Check health probes via port-forward
kubectl port-forward -n tokenization deployment/tokenization-api 8088:8088
curl http://localhost:8088/actuator/health/liveness
curl http://localhost:8088/actuator/health/readiness

# Test via ingress (requires 127.0.0.1 tokenization-api.local in hosts file)
curl -X POST http://tokenization-api.local/api/tokenize \
  -H "Content-Type: application/json" \
  -H "source: test" -H "correlationId: test-001" \
  -d '{"ccNumber":"4111111111111111"}'

# Pod logs
kubectl logs -n tokenization -l app.kubernetes.io/name=tokenization-api --tail=100

# Helm release status
helm status tokenization-api -n tokenization
helm history tokenization-api -n tokenization
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

**Required headers on all requests**: `source` and `correlationId` (validated at controller level).

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
| Oracle DB | localhost:1521/XEPDB1 (local) / host.docker.internal:1521 (Docker/k8s), user: `amit` |
| AWS KMS | Region: `ap-south-1`; CMK ARN in `application.yml`; needs `kms:GenerateDataKey` + `kms:Decrypt` |
| AWS Auth | Static credentials in k8s secret (`aws-credentials`) mounted at `/root/.aws/credentials` |
| Local Registry | `host.docker.internal:5001` — htpasswd auth (user: `admin`) |
| Jenkins | Container named `jenkins`, home at `/root`, kubeconfig at `/root/.kube/config` |

## Configuration

All tunable values are in `src/main/resources/application.yml`. Critical properties:

```yaml
aws.kms.key-id:                      # KMS CMK ARN
aws.region:                          # ap-south-1
tokenization.hmacKeyBase64:          # Base64 HMAC signing key — rotate per policy
tokenization.kms.cache.ttlSeconds:   # Data key cache TTL (default: 30)
tokenization.kms.cache.maxSize:      # Cache max entries (default: 100)
management.server.port:              # Actuator port (default: 8088, same as app)
```

All properties can be overridden via environment variables (`SPRING_DATASOURCE_URL`, `AWS_KMS_KEY_ID`, etc.).

## Health Endpoints (Spring Actuator)

```
GET /actuator/health/liveness   → {"status":"UP"}  — JVM alive (excludes DB)
GET /actuator/health/readiness  → {"status":"UP"}  — JVM + Oracle reachable
```

Kubernetes probes use these endpoints. Liveness excludes DB deliberately — a DB blip should pause traffic (readiness), not restart pods (liveness).

## Database Schema

Table: `CARD_TOKENS` — managed via Flyway (disabled by default, `ddl-auto: none`).
Migration script: `src/main/resources/db/migration/V1__create_card_tokens_table.sql`.

Key columns: `TOKEN` (unique), `PAN_HASH` (unique, HMAC for lookup), `ENCRYPTED_PAN` (BLOB), `NONCE` (RAW 12), `ENCRYPTED_DATA_KEY` (BLOB), `COLLISION_COUNTER`.

**Important**: If the table exists with old column names (`CC_NUMBER_HASH`, `ENCRYPTED_CC_NUMBER`) from a prior version, drop and recreate it using the migration script.

## CI/CD Pipeline

Jenkins pipeline (`Jenkinsfile`, branch `aws_deploy`):
1. **Checkout** — pulls from GitHub
2. **Build & Test** — `./mvnw clean verify`, publishes JUnit results
3. **Docker Login** — authenticates to `host.docker.internal:5001`
4. **Docker Build & Push** — single-stage build (copies pre-built JAR), tags with `$BUILD_NUMBER`
5. **Deploy** — `helm upgrade --install` with `--atomic`; captures pod logs on failure before cleanup
6. **Verify** — `kubectl rollout status`

Jenkins credentials required: `registry-username`, `registry-password`, `db-username`, `db-password`, `hmac-key-base64`.

## Security Notes

- **No full PAN ever logged** — service logs last 4 digits only
- Plaintext data keys are zeroed via `Arrays.fill()` after use (`DataKeyPair.clearPlainDataKey()`)
- HMAC key and DB credentials must be supplied via environment variables in production — never commit real values
- `correlationId` and `source` request headers are required by the API (validated at controller level)
- `insecure-skip-tls-verify: true` in Jenkins kubeconfig is intentional for Docker Desktop (cert SANs exclude `host.docker.internal`) — never use on real clusters
