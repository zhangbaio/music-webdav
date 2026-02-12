package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.AuthTokenResponse;
import com.example.musicwebdav.common.config.AppAuthProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.JwtTokenCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {

    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private static final String TOKEN_RESPONSE_TYPE = "Bearer";

    private static final String CODE_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
    private static final String CODE_REFRESH_EXPIRED = "AUTH_REFRESH_TOKEN_EXPIRED";
    private static final String CODE_REFRESH_INVALID = "AUTH_REFRESH_TOKEN_INVALID";
    private static final String CODE_REFRESH_REVOKED = "AUTH_REFRESH_TOKEN_REVOKED";
    private static final String CODE_ACCESS_INVALID = "AUTH_ACCESS_TOKEN_INVALID";
    private static final String CODE_ACCESS_EXPIRED = "AUTH_ACCESS_TOKEN_EXPIRED";

    private final AppAuthProperties properties;
    private final Clock clock;

    private final ConcurrentMap<String, RefreshSession> refreshSessions = new ConcurrentHashMap<>();

    
    @Autowired
    public AuthTokenService(AppAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AuthTokenService(AppAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public AuthTokenResponse login(String username, String password) {
        if (!safeEquals(properties.getUsername(), username) || !safeEquals(properties.getPassword(), password)) {
            throw new BusinessException(CODE_INVALID_CREDENTIALS, "用户名或密码错误", "请检查账号密码后重试");
        }

        return issueTokenPair(username);
    }

    public AuthTokenResponse refresh(String refreshToken) {
        Map<String, Object> claims = decodeToken(refreshToken, CODE_REFRESH_INVALID, "refresh token 无效");
        validateStandardClaims(claims, TOKEN_TYPE_REFRESH);

        String subject = stringClaim(claims, "sub", CODE_REFRESH_INVALID, "refresh token 缺少 subject");
        String refreshJti = stringClaim(claims, "jti", CODE_REFRESH_INVALID, "refresh token 缺少 jti");
        long now = nowEpochSecond();

        RefreshSession session = refreshSessions.get(refreshJti);
        if (session == null) {
            throw new BusinessException(CODE_REFRESH_REVOKED, "refresh token 已失效", "请重新登录");
        }
        if (session.isRevoked()) {
            throw new BusinessException(CODE_REFRESH_REVOKED, "refresh token 已撤销", "请重新登录");
        }
        if (session.getExpiresAtEpochSecond() <= now) {
            refreshSessions.remove(refreshJti);
            throw new BusinessException(CODE_REFRESH_EXPIRED, "refresh token 已过期", "请重新登录");
        }
        if (!safeEquals(session.getSubject(), subject)) {
            throw new BusinessException(CODE_REFRESH_INVALID, "refresh token subject 不匹配", "请重新登录");
        }

        String nextRefreshToken = refreshToken;
        long refreshTtl = Math.max(1L, properties.getRefreshTokenTtlSeconds());
        long refreshExpiresAt = now + refreshTtl;

        if (properties.isRefreshTokenRotate()) {
            session.setRevoked(true);
            RefreshTokenIssue issue = issueRefreshToken(subject, refreshExpiresAt);
            nextRefreshToken = issue.getToken();
            refreshExpiresAt = issue.getExpiresAtEpochSecond();
        }

        long accessTtl = Math.max(1L, properties.getAccessTokenTtlSeconds());
        long accessExpiresAt = now + accessTtl;
        String accessToken = issueAccessToken(subject, accessExpiresAt);

        return new AuthTokenResponse(
                accessToken,
                nextRefreshToken,
                TOKEN_RESPONSE_TYPE,
                accessTtl,
                refreshExpiresAt - now
        );
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            return;
        }

        try {
            Map<String, Object> claims = decodeToken(refreshToken.trim(), CODE_REFRESH_INVALID, "refresh token 无效");
            String refreshJti = stringClaim(claims, "jti", CODE_REFRESH_INVALID, "refresh token 缺少 jti");
            RefreshSession session = refreshSessions.get(refreshJti);
            if (session != null) {
                session.setRevoked(true);
            }
        } catch (BusinessException ignored) {
            // logout should be idempotent and not leak token validation details.
        }
    }

    public String verifyAccessTokenAndGetSubject(String accessToken) {
        Map<String, Object> claims = decodeToken(accessToken, CODE_ACCESS_INVALID, "access token 无效");
        validateStandardClaims(claims, TOKEN_TYPE_ACCESS);
        return stringClaim(claims, "sub", CODE_ACCESS_INVALID, "access token 缺少 subject");
    }

    private AuthTokenResponse issueTokenPair(String subject) {
        long now = nowEpochSecond();
        long accessTtl = Math.max(1L, properties.getAccessTokenTtlSeconds());
        long refreshTtl = Math.max(1L, properties.getRefreshTokenTtlSeconds());

        long accessExpiresAt = now + accessTtl;
        long refreshExpiresAt = now + refreshTtl;

        String accessToken = issueAccessToken(subject, accessExpiresAt);
        RefreshTokenIssue refreshIssue = issueRefreshToken(subject, refreshExpiresAt);

        return new AuthTokenResponse(
                accessToken,
                refreshIssue.getToken(),
                TOKEN_RESPONSE_TYPE,
                accessTtl,
                refreshTtl
        );
    }

    private String issueAccessToken(String subject, long expiresAtEpochSecond) {
        Map<String, Object> claims = standardClaims(subject, TOKEN_TYPE_ACCESS, expiresAtEpochSecond);
        claims.put("scope", "user");
        return JwtTokenCodec.encode(claims, properties.getJwtSecret());
    }

    private RefreshTokenIssue issueRefreshToken(String subject, long expiresAtEpochSecond) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> claims = standardClaims(subject, TOKEN_TYPE_REFRESH, expiresAtEpochSecond);
        claims.put("jti", jti);

        String token = JwtTokenCodec.encode(claims, properties.getJwtSecret());
        refreshSessions.put(jti, new RefreshSession(subject, expiresAtEpochSecond, false));

        return new RefreshTokenIssue(token, jti, expiresAtEpochSecond);
    }

    private Map<String, Object> standardClaims(String subject, String tokenType, long expiresAtEpochSecond) {
        long now = nowEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.put("typ", tokenType);
        claims.put("iss", properties.getIssuer());
        claims.put("aud", properties.getAudience());
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", expiresAtEpochSecond);
        return claims;
    }

    private void validateStandardClaims(Map<String, Object> claims, String expectedType) {
        long now = nowEpochSecond();

        String tokenType = stringClaim(claims, "typ", CODE_ACCESS_INVALID, "token 缺少 typ");
        if (!safeEquals(expectedType, tokenType)) {
            if (TOKEN_TYPE_REFRESH.equals(expectedType)) {
                throw new BusinessException(CODE_REFRESH_INVALID, "token 类型不正确", "请重新登录");
            }
            throw new BusinessException(CODE_ACCESS_INVALID, "token 类型不正确", "请重新登录");
        }

        String issuer = stringClaim(claims, "iss", CODE_ACCESS_INVALID, "token 缺少 iss");
        if (!safeEquals(properties.getIssuer(), issuer)) {
            throw new BusinessException(CODE_ACCESS_INVALID, "token issuer 不匹配", "请重新登录");
        }

        String audience = stringClaim(claims, "aud", CODE_ACCESS_INVALID, "token 缺少 aud");
        if (!safeEquals(properties.getAudience(), audience)) {
            throw new BusinessException(CODE_ACCESS_INVALID, "token audience 不匹配", "请重新登录");
        }

        long notBefore = longClaim(claims, "nbf", CODE_ACCESS_INVALID, "token 缺少 nbf");
        long expiresAt = longClaim(claims, "exp", CODE_ACCESS_INVALID, "token 缺少 exp");

        if (notBefore > now) {
            throw new BusinessException(CODE_ACCESS_INVALID, "token 尚未生效", "请稍后重试");
        }

        if (expiresAt <= now) {
            if (TOKEN_TYPE_REFRESH.equals(expectedType)) {
                throw new BusinessException(CODE_REFRESH_EXPIRED, "refresh token 已过期", "请重新登录");
            }
            throw new BusinessException(CODE_ACCESS_EXPIRED, "access token 已过期", "请重新登录");
        }
    }

    private Map<String, Object> decodeToken(String token, String code, String message) {
        if (token == null || token.trim().isEmpty()) {
            throw new BusinessException(code, message, "请重新登录");
        }

        try {
            return JwtTokenCodec.decodeAndVerify(token.trim(), properties.getJwtSecret());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(code, message, "请重新登录");
        }
    }

    private String stringClaim(Map<String, Object> claims, String key, String code, String message) {
        Object value = claims.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new BusinessException(code, message, "请重新登录");
        }
        return ((String) value).trim();
    }

    private long longClaim(Map<String, Object> claims, String key, String code, String message) {
        Object value = claims.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new BusinessException(code, message, "请重新登录");
    }

    private long nowEpochSecond() {
        return Instant.now(clock).getEpochSecond();
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static class RefreshTokenIssue {

        private final String token;
        private final String jti;
        private final long expiresAtEpochSecond;

        RefreshTokenIssue(String token, String jti, long expiresAtEpochSecond) {
            this.token = token;
            this.jti = jti;
            this.expiresAtEpochSecond = expiresAtEpochSecond;
        }

        String getToken() {
            return token;
        }

        String getJti() {
            return jti;
        }

        long getExpiresAtEpochSecond() {
            return expiresAtEpochSecond;
        }
    }

    private static class RefreshSession {

        private final String subject;
        private final long expiresAtEpochSecond;
        private volatile boolean revoked;

        RefreshSession(String subject, long expiresAtEpochSecond, boolean revoked) {
            this.subject = subject;
            this.expiresAtEpochSecond = expiresAtEpochSecond;
            this.revoked = revoked;
        }

        String getSubject() {
            return subject;
        }

        long getExpiresAtEpochSecond() {
            return expiresAtEpochSecond;
        }

        boolean isRevoked() {
            return revoked;
        }

        void setRevoked(boolean revoked) {
            this.revoked = revoked;
        }
    }
}
