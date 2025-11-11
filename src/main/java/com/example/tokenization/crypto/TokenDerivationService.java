package com.example.tokenization.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * TokenDerivationService encapsulates deterministic token generation logic.
 *
 * Design:
 * - We compute an HMAC-SHA256 over the raw PAN using a secret key (Base64 in config).
 * - Tokens are 16-digit numeric strings that always start with '9'.
 * - To avoid rare collisions, we can re-HMAC with a small integer counter suffix.
 *
 * Security:
 * - The HMAC key must be kept secret and rotated according to your policy.
 * - Only non-reversible metadata (HMACs) are used for lookups; PANs are encrypted separately.
 */
@Component
public class TokenDerivationService {

    private static final String HMAC_ALGO = "HmacSHA256";

    @Value("${tokenization.hmacKeyBase64}")
    private String hmacKeyBase64;

    /**
     * Computes a hex-encoded HMAC-SHA256 of the credit card number for deterministic lookups.
     */
    public String computeCcNumberHash(String ccNumber) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(hmacKey(), HMAC_ALGO));
        mac.update(ccNumber.getBytes(StandardCharsets.UTF_8));
        byte[] h = mac.doFinal();
        return bytesToHex(h);
    }

    /**
     * Derives a 16-digit token starting with '9' from the hex HMAC of the credit card number.
     * Preserves the last 4 digits of the original credit card number.
     * Adds an optional counter to resolve collisions deterministically.
     * 
     * @param ccNumberHashHex The hex-encoded hash of the credit card number
     * @param counter Collision counter for deterministic retry
     * @param last4Digits Last 4 digits of the original credit card number to preserve
     * @return 16-digit token in format: 9 + 11 random digits + last4Digits
     */
    public String deriveTokenFromHash(String ccNumberHashHex, int counter, String last4Digits) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(hmacKey(), HMAC_ALGO));
        mac.update(ccNumberHashHex.getBytes(StandardCharsets.UTF_8));
        if (counter > 0) {
            mac.update((byte) ':');
            mac.update(Integer.toString(counter).getBytes(StandardCharsets.UTF_8));
        }
        byte[] h = mac.doFinal();
        BigInteger bi = new BigInteger(1, h);
        // First digit forced to '9', derive 11-digit middle section, preserve last 4 digits
        BigInteger mod = BigInteger.TEN.pow(11);
        long middleNum = bi.mod(mod).longValue();
        String middle = String.format("%011d", middleNum);
        return "9" + middle + last4Digits;
    }

    /**
     * Decodes the configured Base64 HMAC key.
     */
    private byte[] hmacKey() {
        return Base64.getDecoder().decode(hmacKeyBase64);
    }

    /**
     * Converts bytes to lowercase hex (no separators).
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexArray = "0123456789abcdef".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
