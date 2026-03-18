package com.example.tokenization.crypto;

import com.example.tokenization.config.TokenizationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class TokenDerivationService {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final byte[] hmacKey;

    public TokenDerivationService(TokenizationProperties props) {
        this.hmacKey = Base64.getDecoder().decode(props.hmacKeyBase64());
    }

    public String computePanHash(String pan) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(hmacKey, HMAC_ALGO));
        mac.update(pan.getBytes(StandardCharsets.UTF_8));
        byte[] h = mac.doFinal();
        return bytesToHex(h);
    }

    public String deriveTokenFromHash(String panHashHex, int counter) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(hmacKey, HMAC_ALGO));
        mac.update(panHashHex.getBytes(StandardCharsets.UTF_8));
        if (counter > 0) {
            mac.update((byte) ':');
            mac.update(Integer.toString(counter).getBytes(StandardCharsets.UTF_8));
        }
        byte[] h = mac.doFinal();
        BigInteger bi = new BigInteger(1, h);
        BigInteger mod = BigInteger.TEN.pow(15);
        long suffixNum = bi.mod(mod).longValue();
        String suffix = String.format("%015d", suffixNum);
        return "9" + suffix;
    }

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
