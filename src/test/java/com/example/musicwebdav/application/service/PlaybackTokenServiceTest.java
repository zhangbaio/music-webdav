package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppAuthProperties;
import com.example.musicwebdav.common.config.AppPlaybackProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackTokenServiceTest {

    private MutableClock clock;
    private PlaybackTokenService playbackTokenService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-02-12T00:00:00Z"));

        AppAuthProperties authProperties = new AppAuthProperties();
        authProperties.setIssuer("music-webdav-test");
        authProperties.setAudience("applemusic-mobile-test");
        authProperties.setJwtSecret("music-webdav-playback-unit-test-secret");

        AppPlaybackProperties playbackProperties = new AppPlaybackProperties();
        playbackProperties.setTokenTtlSeconds(30);
        playbackProperties.setRefreshBeforeExpirySeconds(8);

        playbackTokenService = new PlaybackTokenService(authProperties, playbackProperties, clock);
    }

    @Test
    void shouldIssueAndVerifyTrackStreamToken() {
        PlaybackTokenService.PlaybackTokenIssue issue =
                playbackTokenService.issueTrackStreamToken("demo-user", 9L);

        String subject = playbackTokenService.verifyTrackStreamTokenAndGetSubject(issue.getToken(), 9L);
        Assertions.assertEquals("demo-user", subject);
        Assertions.assertEquals(30L, issue.getTtlSeconds());
        Assertions.assertTrue(issue.getExpiresAtEpochSecond() > issue.getIssuedAtEpochSecond());
    }

    @Test
    void shouldRejectExpiredToken() {
        PlaybackTokenService.PlaybackTokenIssue issue =
                playbackTokenService.issueTrackStreamToken("demo-user", 11L);
        clock.plusSeconds(31);

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> playbackTokenService.verifyTrackStreamTokenAndGetSubject(issue.getToken(), 11L)
        );
        Assertions.assertEquals("PLAYBACK_TOKEN_EXPIRED", exception.getCode());
    }

    @Test
    void shouldRejectTrackMismatch() {
        PlaybackTokenService.PlaybackTokenIssue issue =
                playbackTokenService.issueTrackStreamToken("demo-user", 13L);

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> playbackTokenService.verifyTrackStreamTokenAndGetSubject(issue.getToken(), 14L)
        );
        Assertions.assertEquals("PLAYBACK_TOKEN_TRACK_MISMATCH", exception.getCode());
    }

    @Test
    void shouldRejectTamperedToken() {
        PlaybackTokenService.PlaybackTokenIssue issue =
                playbackTokenService.issueTrackStreamToken("demo-user", 17L);
        String token = issue.getToken();
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> playbackTokenService.verifyTrackStreamTokenAndGetSubject(tampered, 17L)
        );
        Assertions.assertEquals("PLAYBACK_TOKEN_INVALID", exception.getCode());
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
