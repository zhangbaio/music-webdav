package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.AuthTokenResponse;
import com.example.musicwebdav.common.config.AppAuthProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthTokenServiceTest {

    private MutableClock clock;
    private AppAuthProperties properties;
    private AuthTokenService authTokenService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

        properties = new AppAuthProperties();
        properties.setUsername("zhangbiao");
        properties.setPassword("secret");
        properties.setIssuer("music-webdav-test");
        properties.setAudience("applemusic-mobile-test");
        properties.setJwtSecret("music-webdav-unit-test-jwt-secret");
        properties.setAccessTokenTtlSeconds(60);
        properties.setRefreshTokenTtlSeconds(3600);
        properties.setRefreshTokenRotate(true);

        authTokenService = new AuthTokenService(properties, clock);
    }

    @Test
    void shouldLoginAndIssueAccessAndRefreshToken() {
        AuthTokenResponse response = authTokenService.login("zhangbiao", "secret");

        Assertions.assertNotNull(response.getAccessToken());
        Assertions.assertNotNull(response.getRefreshToken());
        Assertions.assertEquals("Bearer", response.getTokenType());
        Assertions.assertEquals(60L, response.getExpiresInSeconds().longValue());
        Assertions.assertEquals(3600L, response.getRefreshExpiresInSeconds().longValue());

        String subject = authTokenService.verifyAccessTokenAndGetSubject(response.getAccessToken());
        Assertions.assertEquals("zhangbiao", subject);
    }

    @Test
    void shouldRefreshAndRotateRefreshToken() {
        AuthTokenResponse login = authTokenService.login("zhangbiao", "secret");

        AuthTokenResponse refreshed = authTokenService.refresh(login.getRefreshToken());

        Assertions.assertNotNull(refreshed.getAccessToken());
        Assertions.assertNotNull(refreshed.getRefreshToken());
        Assertions.assertNotEquals(login.getRefreshToken(), refreshed.getRefreshToken());

        BusinessException revoked = Assertions.assertThrows(
                BusinessException.class,
                () -> authTokenService.refresh(login.getRefreshToken())
        );
        Assertions.assertEquals("AUTH_REFRESH_TOKEN_REVOKED", revoked.getCode());
    }

    @Test
    void shouldRejectExpiredRefreshToken() {
        properties.setRefreshTokenTtlSeconds(1);
        authTokenService = new AuthTokenService(properties, clock);

        AuthTokenResponse login = authTokenService.login("zhangbiao", "secret");
        clock.plusSeconds(2);

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> authTokenService.refresh(login.getRefreshToken())
        );

        Assertions.assertEquals("AUTH_REFRESH_TOKEN_EXPIRED", exception.getCode());
    }

    @Test
    void shouldRejectTamperedRefreshToken() {
        AuthTokenResponse login = authTokenService.login("zhangbiao", "secret");
        String tampered = login.getRefreshToken().substring(0, login.getRefreshToken().length() - 1)
                + (login.getRefreshToken().endsWith("A") ? "B" : "A");

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> authTokenService.refresh(tampered)
        );

        Assertions.assertEquals("AUTH_REFRESH_TOKEN_INVALID", exception.getCode());
    }

    @Test
    void shouldRejectRevokedRefreshTokenAfterLogout() {
        AuthTokenResponse login = authTokenService.login("zhangbiao", "secret");
        authTokenService.logout(login.getRefreshToken());

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> authTokenService.refresh(login.getRefreshToken())
        );

        Assertions.assertEquals("AUTH_REFRESH_TOKEN_REVOKED", exception.getCode());
    }

    @Test
    void shouldRejectUsingRefreshTokenAsAccessToken() {
        AuthTokenResponse login = authTokenService.login("zhangbiao", "secret");

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> authTokenService.verifyAccessTokenAndGetSubject(login.getRefreshToken())
        );

        Assertions.assertEquals("AUTH_ACCESS_TOKEN_INVALID", exception.getCode());
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void plusSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }
    }
}
