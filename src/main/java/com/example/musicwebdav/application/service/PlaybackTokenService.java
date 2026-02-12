package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppAuthProperties;
import com.example.musicwebdav.common.config.AppPlaybackProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.JwtTokenCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlaybackTokenService {

    private static final String TOKEN_TYPE_PLAYBACK = "playback";
    private static final String TOKEN_SCOPE_TRACK_STREAM = "track-stream";

    private static final String CODE_TOKEN_INVALID = "PLAYBACK_TOKEN_INVALID";
    private static final String CODE_TOKEN_EXPIRED = "PLAYBACK_TOKEN_EXPIRED";
    private static final String CODE_TRACK_MISMATCH = "PLAYBACK_TOKEN_TRACK_MISMATCH";
    private static final String CODE_SIGNING_FAILED = "PLAYBACK_SIGNING_FAILED";

    private final AppAuthProperties authProperties;
    private final AppPlaybackProperties playbackProperties;
    private final Clock clock;

    @Autowired
    public PlaybackTokenService(AppAuthProperties authProperties,
                                AppPlaybackProperties playbackProperties) {
        this(authProperties, playbackProperties, Clock.systemUTC());
    }

    PlaybackTokenService(AppAuthProperties authProperties,
                         AppPlaybackProperties playbackProperties,
                         Clock clock) {
        this.authProperties = authProperties;
        this.playbackProperties = playbackProperties;
        this.clock = clock;
    }

    public PlaybackTokenIssue issueTrackStreamToken(String actor, Long trackId) {
        if (trackId == null || trackId <= 0) {
            throw new BusinessException("400", "trackId 不合法", "请刷新后重试");
        }
        if (!StringUtils.hasText(actor)) {
            throw new BusinessException("401", "播放签名身份缺失", "请重新登录后重试");
        }

        long now = nowEpochSecond();
        long ttl = Math.max(5L, playbackProperties.getTokenTtlSeconds());
        long expiresAt = now + ttl;

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", actor.trim());
        claims.put("typ", TOKEN_TYPE_PLAYBACK);
        claims.put("scope", TOKEN_SCOPE_TRACK_STREAM);
        claims.put("tid", trackId);
        claims.put("iss", authProperties.getIssuer());
        claims.put("aud", authProperties.getAudience());
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", expiresAt);

        try {
            String token = JwtTokenCodec.encode(claims, authProperties.getJwtSecret());
            return new PlaybackTokenIssue(token, now, expiresAt, ttl);
        } catch (Exception e) {
            throw new BusinessException(CODE_SIGNING_FAILED, "播放签名生成失败", "请稍后重试");
        }
    }

    public String verifyTrackStreamTokenAndGetSubject(String token, Long expectedTrackId) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放令牌缺失", "请重试播放");
        }
        if (expectedTrackId == null || expectedTrackId <= 0) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放目标无效", "请重试播放");
        }

        Map<String, Object> claims;
        try {
            claims = JwtTokenCodec.decodeAndVerify(token.trim(), authProperties.getJwtSecret());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放令牌无效", "请重试播放");
        }

        String type = stringClaim(claims, "typ", CODE_TOKEN_INVALID, "播放令牌类型错误", "请重试播放");
        if (!TOKEN_TYPE_PLAYBACK.equals(type)) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放令牌类型错误", "请重试播放");
        }

        String scope = stringClaim(claims, "scope", CODE_TOKEN_INVALID, "播放令牌作用域错误", "请重试播放");
        if (!TOKEN_SCOPE_TRACK_STREAM.equals(scope)) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放令牌作用域错误", "请重试播放");
        }

        String issuer = stringClaim(claims, "iss", CODE_TOKEN_INVALID, "播放令牌签发方错误", "请重试播放");
        if (!safeEquals(authProperties.getIssuer(), issuer)) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放令牌签发方错误", "请重试播放");
        }

        String audience = stringClaim(claims, "aud", CODE_TOKEN_INVALID, "播放令牌受众错误", "请重试播放");
        if (!safeEquals(authProperties.getAudience(), audience)) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放令牌受众错误", "请重试播放");
        }

        long now = nowEpochSecond();
        long notBefore = longClaim(claims, "nbf", CODE_TOKEN_INVALID, "播放令牌尚未生效", "请稍后重试");
        long expiresAt = longClaim(claims, "exp", CODE_TOKEN_INVALID, "播放令牌缺少过期时间", "请重试播放");

        if (notBefore > now) {
            throw new BusinessException(CODE_TOKEN_INVALID, "播放令牌尚未生效", "请稍后重试");
        }
        if (expiresAt <= now) {
            throw new BusinessException(CODE_TOKEN_EXPIRED, "播放令牌已过期", "请重试播放");
        }

        long trackId = longClaim(claims, "tid", CODE_TOKEN_INVALID, "播放令牌缺少 trackId", "请重试播放");
        if (trackId != expectedTrackId.longValue()) {
            throw new BusinessException(CODE_TRACK_MISMATCH, "播放令牌与目标歌曲不匹配", "请重试播放");
        }

        return stringClaim(claims, "sub", CODE_TOKEN_INVALID, "播放令牌缺少身份信息", "请重试播放");
    }

    private String stringClaim(Map<String, Object> claims,
                               String key,
                               String code,
                               String message,
                               String userAction) {
        Object value = claims.get(key);
        if (!(value instanceof String) || !StringUtils.hasText((String) value)) {
            throw new BusinessException(code, message, userAction);
        }
        return ((String) value).trim();
    }

    private long longClaim(Map<String, Object> claims,
                           String key,
                           String code,
                           String message,
                           String userAction) {
        Object value = claims.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && StringUtils.hasText((String) value)) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new BusinessException(code, message, userAction);
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

    public static final class PlaybackTokenIssue {
        private final String token;
        private final long issuedAtEpochSecond;
        private final long expiresAtEpochSecond;
        private final long ttlSeconds;

        PlaybackTokenIssue(String token, long issuedAtEpochSecond, long expiresAtEpochSecond, long ttlSeconds) {
            this.token = token;
            this.issuedAtEpochSecond = issuedAtEpochSecond;
            this.expiresAtEpochSecond = expiresAtEpochSecond;
            this.ttlSeconds = ttlSeconds;
        }

        public String getToken() {
            return token;
        }

        public long getIssuedAtEpochSecond() {
            return issuedAtEpochSecond;
        }

        public long getExpiresAtEpochSecond() {
            return expiresAtEpochSecond;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }
    }
}
