# Configuration Guide

This service is configured primarily via `src/main/resources/application.yml`. All properties can be overridden via environment variables or command-line args.

## Properties

### Server
- `server.port`: HTTP port (default 8088).

### Database (Oracle)
- `spring.datasource.url`: JDBC URL, e.g., `jdbc:oracle:thin:@//host:1521/ORCLPDB1`.
- `spring.datasource.username`: DB user.
- `spring.datasource.password`: DB password.
- `spring.jpa.hibernate.ddl-auto`: `none` (recommended in prod).
- `spring.jpa.database-platform`: `org.hibernate.dialect.OracleDialect` (Hibernate 6).
- `spring.jpa.properties.hibernate.dialect`: Same as above, explicit safeguard for some environments.

### AWS KMS
- `aws.kms.key-id` (required): KeyId or full ARN of the KMS key for data key generation.
- `aws.region`: Region string, e.g., `ap-south-1`.
- `aws.profile` (optional): Named AWS credentials profile (e.g., `rolesanywhere`). If omitted, default provider chain applies.

### Tokenization
- `tokenization.hmacKeyBase64` (required): Base64-encoded HMAC key used for deterministic tokenization and panHash. Keep secret and rotate per policy.
- `tokenization.kms.cache.maxSize` (optional): Maximum number of cached decrypted data keys (default: 100).
- `tokenization.kms.cache.ttlSeconds` (optional): Cache TTL in seconds for decrypted data keys (default: 30).

### Flyway
- `spring.flyway.enabled`: `false` by default. Enable if you want automatic DB migrations.

## Example application.yml
```yaml
server:
  port: 8088
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/ORCLPDB1
    username: token_user
    password: secret
  jpa:
    hibernate:
      ddl-auto: none
    database-platform: org.hibernate.dialect.OracleDialect
    properties:
      hibernate.dialect: org.hibernate.dialect.OracleDialect
  flyway:
    enabled: false
aws:
  kms:
    key-id: arn:aws:kms:ap-south-1:123456789012:key/11111111-2222-3333-4444-555555555555
  region: ap-south-1
  profile: rolesanywhere
# Base64 for 32 bytes (example only; do not use in prod)
tokenization:
  hmacKeyBase64: bXktc3VwZXItc2VjcmV0LWhtYWMta2V5LWFzZS1iYXNlNjQ=
  kms:
    cache:
      maxSize: 100
      ttlSeconds: 30
```

## Environment overrides (PowerShell)
```powershell
$env:SERVER_PORT=8088
$env:SPRING_DATASOURCE_URL='jdbc:oracle:thin:@//localhost:1521/ORCLPDB1'
$env:SPRING_DATASOURCE_USERNAME='token_user'
$env:SPRING_DATASOURCE_PASSWORD='secret'
$env:SPRING_JPA_DATABASE_PLATFORM='org.hibernate.dialect.OracleDialect'
$env:SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT='org.hibernate.dialect.OracleDialect'
$env:AWS_REGION='ap-south-1'
$env:AWS_PROFILE='rolesanywhere'
$env:TOKENIZATION_HMACKEYBASE64='bXktc3VwZXItc2VjcmV0LWhtYWMta2V5LWFzZS1iYXNlNjQ='
java -jar target/tokenization-api-*.jar
```

## Notes
- Ensure the Oracle driver is available (managed via Maven).
- The service requires DB connectivity on startup for JPA.
- For KMS, verify credentials (`AWS_PROFILE` or default provider chain) and region.
