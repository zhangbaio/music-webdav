package com.example.musicwebdav.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class JwtTokenCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private JwtTokenCodec() {
    }

    public static String encode(Map<String, Object> claims, String secret) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            String headerSegment = base64UrlEncode(OBJECT_MAPPER.writeValueAsBytes(header));
            String payloadSegment = base64UrlEncode(OBJECT_MAPPER.writeValueAsBytes(claims));
            String unsignedToken = headerSegment + "." + payloadSegment;
            String signatureSegment = base64UrlEncode(hmacSha256(unsignedToken, secret));

            return unsignedToken + "." + signatureSegment;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode JWT token", e);
        }
    }

    public static Map<String, Object> decodeAndVerify(String token, String secret) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("JWT token format is invalid");
            }

            String unsignedToken = parts[0] + "." + parts[1];
            byte[] expected = hmacSha256(unsignedToken, secret);
            byte[] actual = base64UrlDecode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("JWT signature verification failed");
            }

            Map<String, Object> header = OBJECT_MAPPER.readValue(base64UrlDecode(parts[0]), MAP_TYPE);
            Object alg = header.get("alg");
            if (!(alg instanceof String) || !"HS256".equals(alg)) {
                throw new IllegalArgumentException("JWT algorithm is not supported");
            }

            return OBJECT_MAPPER.readValue(base64UrlDecode(parts[1]), MAP_TYPE);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT parse failed", e);
        }
    }

    private static byte[] hmacSha256(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
