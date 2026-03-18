# Configuration Guide

All properties are in `src/main/resources/application.yml` and can be overridden via environment variables or command-line args.

---

## Properties Reference

### Server

| Property | Default | Description |
|---|---|---|
| `server.port` | `8088` | HTTP application port |

### Database (Oracle)

| Property | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:oracle:thin:@//localhost:1521/XEPDB1` | JDBC URL |
| `spring.datasource.username` | `amit` | DB user |
| `spring.datasource.password` | — | DB password (supply via env) |
| `spring.datasource.driver-class-name` | `oracle.jdbc.OracleDriver` | Driver |
| `spring.jpa.hibernate.ddl-auto` | `none` | Never auto-create schema in prod |
| `spring.jpa.database-platform` | `org.hibernate.dialect.OracleDialect` | Hibernate 6 dialect |

### AWS KMS

| Property | Default | Description |
|---|---|---|
| `aws.kms.key-id` | — | Required. CMK ARN or key ID |
| `aws.region` | `ap-south-1` | AWS region |
| `aws.profile` | — | Optional named credentials profile |

### Tokenization

| Property | Default | Description |
|---|---|---|
| `tokenization.hmacKeyBase64` | — | Required. Base64-encoded 32-byte HMAC key. Keep secret; rotate per policy |
| `tokenization.kms.cache.maxSize` | `100` | Max cached decrypted data keys |
| `tokenization.kms.cache.ttlSeconds` | `30` | Cache TTL seconds — balance security vs KMS cost |

### Spring Actuator (Health Probes)

| Property | Value | Description |
|---|---|---|
| `management.server.port` | `8088` | Actuator port (same as app port) |
| `management.endpoints.web.exposure.include` | `health` | Only health is exposed |
| `management.endpoint.health.show-details` | `never` | No internals in probe responses |
| `management.endpoint.health.probes.enabled` | `true` | Enables `/liveness` and `/readiness` subpaths |
| Liveness group | `livenessState` | JVM alive — excludes DB |
| Readiness group | `readinessState,db` | JVM + Oracle must be reachable |

### Flyway

| Property | Default | Description |
|---|---|---|
| `spring.flyway.enabled` | `false` | Migrations disabled by default; run DDL manually or enable for automation |

---

## Environment Variable Overrides

Spring Boot maps `SPRING_DATASOURCE_URL` → `spring.datasource.url`, etc.

```bash
# Database
export SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//host.docker.internal:1521/XEPDB1
export SPRING_DATASOURCE_USERNAME=amit
export SPRING_DATASOURCE_PASSWORD=welcome123

# AWS
export AWS_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...

# Tokenization
export TOKENIZATION_HMACKEYBASE64=<base64-key>

java -jar target/tokenization-service-0.0.1-SNAPSHOT.jar
```

---

## Kubernetes / Helm Configuration

Helm chart at `helm/tokenization-api/`. Values in `helm/tokenization-api/values.yaml`.

**Secrets** are injected at deploy time via `--set` — never stored in `values.yaml`:

```bash
helm upgrade --install tokenization-api helm/tokenization-api \
  --namespace tokenization \
  --set image.tag=<BUILD_NUMBER> \
  --set secrets.dbUsername=amit \
  --set secrets.dbPassword=<password> \
  --set secrets.hmacKeyBase64=<key>
```

**Key values defaults**:

| Value | Default | Description |
|---|---|---|
| `image.repository` | `host.docker.internal:5001/tokenization-api` | Registry + image name |
| `image.tag` | `latest` | Always override with build number |
| `image.pullPolicy` | `IfNotPresent` | Use `Always` if `latest` tag is reused |
| `datasource.url` | `jdbc:oracle:thin:@//host.docker.internal:1521/XEPDB1` | Oracle via host |
| `management.port` | `8088` | Actuator probe port |
| `management.liveness.path` | `/actuator/health/liveness` | Startup + liveness path |
| `management.readiness.path` | `/actuator/health/readiness` | Readiness path |
| `probes.liveness.initialDelaySeconds` | `60` | |
| `probes.readiness.initialDelaySeconds` | `30` | |
| `ingress.host` | `tokenization-api.local` | Add to hosts file |
| `existingSecrets.awsCredentials` | `aws-credentials` | K8s secret with `credentials` key |
| `existingSecrets.appSecret` | `tokenization-secret` | K8s secret with DB + HMAC credentials |

**Pre-existing secrets** (created by `k8s/bootstrap.sh`):

```bash
# AWS credentials — mounted at /root/.aws/credentials
kubectl get secret aws-credentials -n tokenization

# App secrets (DB user/pass + HMAC key) — injected as env vars
kubectl get secret tokenization-secret -n tokenization
```

---

## JVM Options

Configured via `javaOpts` in `values.yaml` (Kubernetes) or `JAVA_OPTS` env var (local/Docker):

```
-XX:+UseG1GC -XX:MaxGCPauseMillis=200
-Xms512m -Xmx768m
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/heapdumps
```

For Java 21 high-throughput: substitute `-XX:+UseZGC -XX:+ZGenerational`.
