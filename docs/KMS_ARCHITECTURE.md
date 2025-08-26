# KMS Data Key Service Architecture

## Overview
The `KmsDataKeyService` encapsulates all AWS KMS operations and provides intelligent caching for better performance and cost optimization.

## Components

### KmsDataKeyService
**Location**: `src/main/java/com/example/tokenization/kms/KmsDataKeyService.java`

**Responsibilities**:
- Generate new AES-256 data keys from AWS KMS
- Decrypt encrypted data keys with caching
- Manage cache keys using Base64-encoded encrypted data keys
- Provide security-conscious memory management

**Key Methods**:
- `generateDataKey()`: Creates new data key from KMS
- `decryptDataKey(byte[])`: Decrypts with caching
- `generateAndExtractDataKey()`: Convenience method returning `DataKeyPair`

### DataKeyCache
**Location**: `src/main/java/com/example/tokenization/kms/DataKeyCache.java`

**Features**:
- Caffeine-based in-memory cache
- Bounded size (default: 100 entries)
- Time-based expiration (default: 30 seconds)
- Thread-safe operations

### DataKeyPair
**Inner class**: `KmsDataKeyService.DataKeyPair`

**Purpose**:
- Holds both plaintext and encrypted data key
- Provides `clearPlainDataKey()` for secure cleanup
- Immutable after construction

## Security Model

### Data Key Generation (Tokenization)
1. **Always generates unique data keys**: Each PAN gets its own data key from KMS
2. **No key reuse**: Maximum security through key isolation
3. **Immediate cleanup**: Plaintext keys zeroed after encryption

### Data Key Decryption (Detokenization)
1. **Cache-first approach**: Check cache before KMS call
2. **Copy protection**: Returns copies to prevent cache pollution
3. **Bounded exposure**: Short TTL and size limits
4. **Secure cleanup**: Caller responsible for zeroing returned keys

## Performance Characteristics

### Cache Hit Scenario
- **Latency**: ~1-5ms (in-memory lookup)
- **Cost**: No KMS charges
- **Use case**: Recent detokenizations

### Cache Miss Scenario
- **Latency**: ~50-200ms (KMS decrypt call)
- **Cost**: Standard KMS decrypt charges
- **Side effect**: Populates cache for future hits

## Configuration

```yaml
tokenization:
  kms:
    cache:
      maxSize: 100        # Tune based on memory constraints
      ttlSeconds: 30      # Balance security vs performance
```

### Tuning Guidelines
- **High volume**: Increase `maxSize` (e.g., 500-1000)
- **Security focused**: Decrease `ttlSeconds` (e.g., 10-15)
- **Cost optimization**: Increase `ttlSeconds` (e.g., 60-300)

## Integration Points

### TokenizationService Changes
- **Before**: Direct KMS client usage
- **After**: Uses `KmsDataKeyService` abstraction
- **Benefits**: Cleaner separation of concerns, easier testing

### Logging
- **Cache hits/misses**: Logged at DEBUG level
- **Cache keys**: Only first 10 characters logged (truncated for security)
- **No plaintext keys**: Never logged in any form

## Migration Notes

### Backward Compatibility
- **Database schema**: No changes required
- **API contracts**: Unchanged
- **Existing tokens**: Fully compatible

### Monitoring Recommendations
- Monitor cache hit rates via application metrics
- Track KMS API call frequency and costs
- Alert on cache memory usage if needed

## Future Enhancements

### Potential Optimizations
1. **Metrics integration**: Expose cache statistics via Micrometer
2. **Background refresh**: Proactively refresh near-expired entries
3. **Distributed caching**: Redis/Hazelcast for multi-instance deployments
4. **Key rotation**: Handle KMS key rotation scenarios

### Alternative Patterns
1. **Data key pooling**: Pre-generate and pool data keys
2. **Hierarchical keys**: Derive data keys from master keys
3. **Time-based key rotation**: Automatic key rotation policies
