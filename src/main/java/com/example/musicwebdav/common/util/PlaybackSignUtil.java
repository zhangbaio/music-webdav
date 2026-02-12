package com.example.musicwebdav.common.util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 based signing / verification for playback stream URLs.
 * <p>
 * Signed payload format: "{trackId}:{expireEpochSecond}"
 * Signature is a lowercase hex string.
 */
public final class PlaybackSignUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private PlaybackSignUtil() {
    }

    /**
     * Generate a hex-encoded HMAC-SHA256 signature.
     *
     * @param signKey            HMAC key
     * @param trackId            track id to bind
     * @param expireEpochSecond  expiration timestamp (epoch seconds)
     * @return lowercase hex signature string
     */
    public static String sign(String signKey, long trackId, long expireEpochSecond) {
        String payload = trackId + ":" + expireEpochSecond;
        byte[] hash = hmacSha256(signKey, payload);
        return bytesToHex(hash);
    }

    /**
     * Verify that a signature is valid and not expired.
     *
     * @param signKey            HMAC key
     * @param trackId            track id
     * @param expireEpochSecond  expiration timestamp (epoch seconds)
     * @param signature          hex signature to verify
     * @return true if signature matches AND current time < expire
     */
    public static boolean verify(String signKey, long trackId, long expireEpochSecond, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        // Check expiration first
        long now = System.currentTimeMillis() / 1000;
        if (now >= expireEpochSecond) {
            return false;
        }
        String expected = sign(signKey, trackId, expireEpochSecond);
        return constantTimeEquals(expected, signature);
    }

    private static byte[] hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKey);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
