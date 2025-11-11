# Instructions to Revert AWS KMS Changes

## Overview
The code has been temporarily modified to work without AWS KMS by using a property-based encryption key. This allows testing the API when AWS KMS is unavailable.

## Files Modified

### 1. `TokenizationService.java`
**Location**: `src/main/java/com/example/tokenization/service/TokenizationService.java`

**To Revert:**
- Uncomment all KMS-related imports at the top
- Uncomment `private final KmsClient kmsClient;`
- Uncomment `private final AwsKmsConfig awsKmsConfig;`
- Uncomment `import com.example.tokenization.config.AwsKmsConfig;`
- Remove `@Value("${tokenization.encryptionKeyBase64}")` and `encryptionKeyBase64` field
- Remove `import java.util.Base64;`
- In `tokenize()` method: Uncomment the KMS GenerateDataKey code block
- In `tokenize()` method: Remove the temporary property-based key code
- In `detokenize()` method: Uncomment the KMS Decrypt code block
- In `detokenize()` method: Remove the temporary property-based key code

### 2. `AwsKmsConfig.java`
**Location**: `src/main/java/com/example/tokenization/config/AwsKmsConfig.java`

**To Revert:**
- Uncomment all AWS SDK imports
- Uncomment `@Bean` import
- Uncomment the entire `kmsClient()` bean method

### 3. `KmsDataKeyService.java`
**Location**: `src/main/java/com/example/tokenization/kms/KmsDataKeyService.java`

**To Revert:**
- Uncomment all imports
- Uncomment `@Service` annotation
- Uncomment `@RequiredArgsConstructor` annotation
- Uncomment all class fields
- Uncomment all methods (generateDataKey, decryptDataKey, generateAndExtractDataKey)
- Uncomment the DataKeyPair inner class

### 4. `application.yml`
**Location**: `src/main/resources/application.yml`

**To Revert:**
- Remove the `encryptionKeyBase64` property from the tokenization section

## Quick Steps to Revert

1. Search for comments containing "COMMENTED OUT FOR TESTING WITHOUT AWS KMS"
2. Uncomment all code blocks marked with these comments
3. Remove all code marked as "TEMPORARY: Use property-based key"
4. Remove the `encryptionKeyBase64` property from application.yml
5. Run `mvn clean compile` to verify

## Testing After Revert

Ensure AWS KMS is accessible and credentials are configured properly before reverting. Test with:
```bash
mvn spring-boot:run
```

## Current Temporary Configuration

The encryption key is currently sourced from:
```yaml
tokenization:
  encryptionKeyBase64: "MTIzNDU2Nzg5MGFiY2RlZjEyMzQ1Njc4OTBhYmNkZWY="
```

**Note**: This is a hardcoded Base64-encoded AES-256 key and should NEVER be used in production. It's only for local testing without KMS.
