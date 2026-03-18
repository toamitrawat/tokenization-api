# Tokenization API

Deterministic credit-card tokenization API built with Spring Boot 3 / Java 21. Generates a stable 16-digit token (always starting with `9`) for a given 16-digit card number and stores encrypted PAN data with AWS KMS-backed envelope encryption.

- **Deterministic tokens**: same PAN → same token (prefix `9`), with collision handling
- **Envelope encryption**: PAN encrypted with AES-256-GCM; data keys generated and protected by AWS KMS
- **Persistence**: Oracle XE via Spring Data JPA; deterministic lookups by HMAC-SHA256 `panHash`
- **Production-ready**: Spring Actuator health probes, structured JSON logging, Helm chart, Jenkins CI/CD

## Contents

- [Architecture](#architecture)
- [API](#api)
- [Health Endpoints](#health-endpoints)
- [Build & Run](#build--run)
- [Docker Compose](#docker-compose)
- [Kubernetes / Helm](#kubernetes--helm)
- [CI/CD (Jenkins)](#cicd-jenkins)
- [Configuration](#configuration)
- [Database Schema](#database-schema)
- [Logging & Observability](#logging--observability)
- [Error Handling](#error-handling)
- [Security Considerations](#security-considerations)
- [Troubleshooting](#troubleshooting)

---

## Architecture

**Stack**: Spring Boot 3.3.2 · Java 21 · Spring Data JPA · Oracle XE · AWS SDK v2 (KMS) · Caffeine cache

**Key components**:
| Component | Responsibility |
|---|---|
| `TokenController` | REST endpoints; enforces `source` + `correlationId` headers; populates MDC |
| `TokenizationService` | Orchestrates tokenize/detokenize; `@Transactional`; logs masked PAN only |
| `KmsDataKeyService` | AWS KMS operations with Caffeine-backed data key cache (TTL 30s, max 100) |
| `TokenDerivationService` | HMAC-SHA256 panHash + 16-digit token derivation |
| `CardToken` | JPA entity → `CARD_TOKENS` table |
| `GlobalExceptionHandler` | `@RestControllerAdvice`; maps to 400/404/500 |

**Tokenize flow**:
1. Validate input (exactly 16 digits)
2. Compute HMAC `panHash`; look up existing token — return it if found
3. KMS generates AES-256 data key → AES-256-GCM encrypt PAN → derive deterministic token → persist
4. Return 201 + `Location` header

**Detokenize flow**:
1. Look up by token
2. KMS decrypts data key (cache-first) → AES-GCM decrypt → return PAN

---

## API

See [docs/API.md](docs/API.md) for full reference.

**Required headers on every request**: `source` and `correlationId`

**Quick reference**:

```bash
# Tokenize
curl -X POST http://tokenization-api.local/api/tokenize \
  -H "Content-Type: application/json" \
  -H "source: my-service" \
  -H "correlationId: req-001" \
  -d '{"ccNumber":"4111111111111111"}'
# → 201  {"token":"9142960579605051"}

# Detokenize
curl "http://tokenization-api.local/api/detokenize?token=9142960579605051" \
  -H "source: my-service" \
  -H "correlationId: req-002"
# → 200  {"ccNumber":"4111111111111111"}
```

---

## Health Endpoints

Spring Actuator exposes liveness and readiness probes at port 8088:

```bash
GET /actuator/health/liveness   # {"status":"UP"} — JVM alive (no DB check)
GET /actuator/health/readiness  # {"status":"UP"} — JVM + Oracle reachable
```

Liveness excludes the DB check deliberately — a DB blip should pause traffic (readiness), not restart pods (liveness).

---

## Build & Run

**Prerequisites**: Java 21, Oracle XE reachable at `localhost:1521/XEPDB1`, AWS credentials

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Build with tests
./mvnw clean verify

# Run
java -jar target/tokenization-service-0.0.1-SNAPSHOT.jar

# With env overrides
export SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//localhost:1521/XEPDB1
export AWS_REGION=ap-south-1
java -jar target/tokenization-service-0.0.1-SNAPSHOT.jar
```

---

## Docker Compose

Runs the API against a local Oracle XE container.

```bash
# Start (Oracle XE must already be running separately or via compose)
docker compose up -d --build
docker compose logs -f app
docker compose down -v
```

Environment overrides via `.env` file (see `.env.example`).

---

## Kubernetes / Helm

Deploys to Docker Desktop Kubernetes as an EKS dry-run using the Helm chart in `helm/tokenization-api/`.

### One-time bootstrap

```bash
# From Git Bash — set env vars first
export REGISTRY_USER=admin
export REGISTRY_PASS=<registry-password>
export AWS_ACCESS_KEY_ID=<key>
export AWS_SECRET_ACCESS_KEY=<secret>

bash k8s/bootstrap.sh
```

This installs nginx ingress controller, creates the `tokenization` namespace, and creates the required Kubernetes secrets (`registry-secret`, `aws-credentials`).

**Add to Windows hosts file** (Notepad as Administrator → `C:\Windows\System32\drivers\etc\hosts`):
```
127.0.0.1  tokenization-api.local
```

### Start Jenkins

```bash
bash k8s/jenkins-setup.sh
```

Starts Jenkins, installs Docker CLI v26.1.4 / kubectl v1.32.2 / helm v3.17.1 inside the container, and injects a Docker Desktop kubeconfig.

### Manual Helm deploy

```bash
helm upgrade --install tokenization-api helm/tokenization-api \
  --namespace tokenization \
  --set image.tag=<tag> \
  --set secrets.dbUsername=amit \
  --set secrets.dbPassword=<password> \
  --set secrets.hmacKeyBase64=<key>
```

### Verify deployment

```bash
kubectl get pods -n tokenization -w
curl http://tokenization-api.local/actuator/health/liveness
curl http://tokenization-api.local/actuator/health/readiness
```

---

## CI/CD (Jenkins)

Pipeline defined in `Jenkinsfile` (branch `aws_deploy`):

| Stage | Action |
|---|---|
| Checkout | Pull from GitHub |
| Build & Test | `./mvnw clean verify` + JUnit results |
| Docker Login | Authenticate to `host.docker.internal:5001` |
| Docker Build & Push | Single-stage build, tag = `$BUILD_NUMBER` |
| Deploy | `helm upgrade --install --atomic`; captures pod logs on failure |
| Verify | `kubectl rollout status` |

**Jenkins credentials** (Manage Jenkins > Credentials > Global > Secret text):

| ID | Value |
|---|---|
| `registry-username` | `admin` |
| `registry-password` | Registry admin password |
| `db-username` | `amit` |
| `db-password` | Oracle password |
| `hmac-key-base64` | Base64 HMAC key |

**Pipeline job setup**: New Item > Pipeline > SCM > Git > branch `aws_deploy` > Script Path: `Jenkinsfile`

---

## Configuration

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for full reference.

Critical properties in `src/main/resources/application.yml`:

| Property | Description |
|---|---|
| `aws.kms.key-id` | KMS CMK ARN |
| `aws.region` | `ap-south-1` |
| `tokenization.hmacKeyBase64` | Base64 HMAC signing key — keep secret, rotate per policy |
| `tokenization.kms.cache.ttlSeconds` | Data key cache TTL (default: 30) |
| `tokenization.kms.cache.maxSize` | Cache max entries (default: 100) |
| `management.server.port` | Actuator port (default: 8088) |

---

## Database Schema

Table: `CARD_TOKENS` — `ddl-auto: none`; Flyway disabled by default.
Migration: `src/main/resources/db/migration/V1__create_card_tokens_table.sql`

| Column | Type | Notes |
|---|---|---|
| `TOKEN` | VARCHAR2(64) | Unique, 16-digit token |
| `PAN_HASH` | VARCHAR2(64) | Unique, HMAC-SHA256 of PAN |
| `ENCRYPTED_PAN` | BLOB | AES-256-GCM ciphertext |
| `NONCE` | RAW(12) | AES-GCM IV |
| `ENCRYPTED_DATA_KEY` | BLOB | KMS-wrapped data key |
| `COLLISION_COUNTER` | NUMBER | Incremented on token collision |

---

## Logging & Observability

- Structured JSON logs via `logstash-logback-encoder`
- MDC fields on every request: `source`, `correlationId`
- Only last 4 digits of PAN ever logged — never the full PAN
- Health probes available at `/actuator/health/liveness` and `/actuator/health/readiness`

---

## Error Handling

| Scenario | HTTP | Body |
|---|---|---|
| Invalid `ccNumber` | 400 | `{"ccNumber":"ccNumber must be exactly 16 digits"}` |
| Token not found | 404 | `{"error":"Token not found"}` |
| Internal failure | 500 | `{"error":"Tokenization failed"}` |
| Missing required header | 400 | Validation error |

---

## Security Considerations

- Secrets (`hmacKeyBase64`, DB password) supplied via environment variables — never committed
- Plaintext data keys zeroed via `Arrays.fill()` after use
- Each PAN gets a unique KMS data key — no key reuse
- AES-GCM with unique 12-byte nonce per encryption
- `source` and `correlationId` headers required on all requests

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `ORA-00904: PAN_HASH invalid identifier` | Table created with old schema | Drop and recreate using `V1__create_card_tokens_table.sql` |
| Readiness probe timeout | Oracle unreachable | Ensure `oracle-xe` container is running: `docker start oracle-xe` |
| `COPY failed: no source files` in Docker | `.dockerignore` blocked JAR | Ensure `!target/tokenization-service-*.jar` is in `.dockerignore` |
| TLS cert error in Jenkins kubectl | kubeconfig uses wrong server URL | Re-run `k8s/jenkins-setup.sh` |
| `client version too old` Docker error | Old Docker CLI in Jenkins | Re-run `k8s/jenkins-setup.sh` to install Docker CLI v26.1.4 |
| KMS credential errors | AWS credentials not mounted | Verify `aws-credentials` secret exists: `kubectl get secret aws-credentials -n tokenization` |
