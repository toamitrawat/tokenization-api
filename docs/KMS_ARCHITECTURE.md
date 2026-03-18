# KMS Architecture

## Overview

The `KmsDataKeyService` encapsulates all AWS KMS operations and provides intelligent caching for performance and cost optimisation. It implements envelope encryption: KMS protects the data key, the data key protects the PAN.

---

## Components

### KmsDataKeyService
**Location**: `src/main/java/com/example/tokenization/kms/KmsDataKeyService.java`

**Responsibilities**:
- Generate AES-256 data keys from AWS KMS (`kms:GenerateDataKey`)
- Decrypt encrypted data keys with cache-first lookup (`kms:Decrypt`)
- Manage cache keys using Base64-encoded encrypted data keys as cache identifiers
- Zero plaintext key material after use

**Key methods**:
- `generateAndExtractDataKey()` → `DataKeyPair` — used during tokenization
- `decryptDataKey(byte[])` → `byte[]` — used during detokenization (cache-first)

### DataKeyCache
**Location**: `src/main/java/com/example/tokenization/kms/DataKeyCache.java`

- Caffeine in-memory cache, thread-safe
- Bounded size (default: 100 entries)
- Time-based expiration (default: 30 seconds TTL)
- Returns defensive copies — prevents cache pollution from caller mutations

### DataKeyPair
**Inner class of `KmsDataKeyService`**

Holds both the plaintext key and the encrypted key returned by KMS. Call `clearPlainDataKey()` immediately after the plaintext key is no longer needed — it calls `Arrays.fill(key, (byte) 0)`.

---

## Security Model

### Tokenization (key generation)
1. KMS generates a unique AES-256 data key per PAN — no key reuse across PANs
2. Plaintext key encrypts the PAN via AES-256-GCM with a random 12-byte nonce
3. Plaintext key zeroed from memory immediately after encryption
4. Encrypted data key and ciphertext persisted to `CARD_TOKENS`

### Detokenization (key decryption)
1. Check cache using Base64(encryptedDataKey) as cache key
2. Cache hit → return copy of cached plaintext key (~1–5ms, no KMS charge)
3. Cache miss → KMS decrypt → cache result → return copy (~50–200ms, KMS charge)
4. Caller responsible for zeroing the returned key after use

### AWS Authentication
In Kubernetes, the `aws-credentials` secret is mounted at `/root/.aws/credentials`. The AWS SDK default credential chain reads this file. For local development, the default provider chain (env vars, `~/.aws/credentials`) applies.

---

## Performance

| Scenario | Latency | KMS Cost |
|---|---|---|
| Cache hit | ~1–5ms | None |
| Cache miss | ~50–200ms | Standard decrypt charge |

**Without cache**: every detokenization = 1 KMS call.
**With cache**: repeated detokenizations of recently used tokens skip KMS entirely.

---

## Configuration

```yaml
tokenization:
  kms:
    cache:
      maxSize: 100       # tune based on memory and concurrency
      ttlSeconds: 30     # balance security vs KMS cost
```

**Tuning guidelines**:
- High volume → increase `maxSize` (e.g., 500–1000)
- Security-focused → decrease `ttlSeconds` (e.g., 10–15s)
- Cost-optimised → increase `ttlSeconds` (e.g., 60–300s)

---

## Logging

- Cache hits/misses logged at DEBUG level
- Only first 10 chars of cache key logged (truncated)
- Plaintext keys never logged in any form

---

## Future Enhancements

1. **Micrometer metrics** — expose cache hit rate, miss rate, KMS call latency via `/actuator/metrics`
2. **Distributed caching** — Redis/Hazelcast for multi-replica deployments (currently per-pod cache)
3. **Background refresh** — proactively refresh near-expired entries to avoid cold misses
4. **KMS key rotation** — handle re-encryption on CMK rotation events
