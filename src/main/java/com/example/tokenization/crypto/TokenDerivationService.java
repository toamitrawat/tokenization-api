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
     * Computes a hex-encoded HMAC-SHA256 of the PAN for deterministic lookups.
     */
    public String computePanHash(String pan) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(hmacKey(), HMAC_ALGO));
        mac.update(pan.getBytes(StandardCharsets.UTF_8));
        byte[] h = mac.doFinal();
        return bytesToHex(h);
    }

    /**
     * Derives a 16-digit token starting with '9' from the hex HMAC of the PAN.
     * Adds an optional counter to resolve collisions deterministically.
     */
    public String deriveTokenFromHash(String panHashHex, int counter) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(hmacKey(), HMAC_ALGO));
        mac.update(panHashHex.getBytes(StandardCharsets.UTF_8));
        if (counter > 0) {
            mac.update((byte) ':');
            mac.update(Integer.toString(counter).getBytes(StandardCharsets.UTF_8));
        }
        byte[] h = mac.doFinal();
        BigInteger bi = new BigInteger(1, h);
        // First digit forced to '9', derive 15-digit suffix deterministically
        BigInteger mod = BigInteger.TEN.pow(15);
        long suffixNum = bi.mod(mod).longValue();
        String suffix = String.format("%015d", suffixNum);
        return "9" + suffix;
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
