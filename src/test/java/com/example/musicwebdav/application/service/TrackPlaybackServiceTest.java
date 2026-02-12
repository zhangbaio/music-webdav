package com.example.musicwebdav.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.musicwebdav.api.response.PlaybackSessionResponse;
import com.example.musicwebdav.common.config.AppPlaybackProperties;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class TrackPlaybackServiceTest {

    @Test
    void createPlaybackSessionShouldReturnSignedPathAndMetrics() {
        TrackMapper trackMapper = mock(TrackMapper.class);
        WebDavConfigMapper webDavConfigMapper = mock(WebDavConfigMapper.class);
        WebDavClient webDavClient = mock(WebDavClient.class);
        PlaybackTokenService playbackTokenService = mock(PlaybackTokenService.class);
        PlaybackControlService playbackControlService = mock(PlaybackControlService.class);

        TrackEntity track = new TrackEntity();
        track.setId(21L);
        track.setSourcePath("/library/song-21.flac");
        when(trackMapper.selectById(21L)).thenReturn(track);
        when(playbackTokenService.issueTrackStreamToken("demo-user", 21L))
                .thenReturn(new PlaybackTokenService.PlaybackTokenIssue("play-token-21", 1000L, 1060L, 60L));

        AppSecurityProperties securityProperties = new AppSecurityProperties();
        AppPlaybackProperties playbackProperties = new AppPlaybackProperties();
        playbackProperties.setRefreshBeforeExpirySeconds(8);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrackPlaybackService service = new TrackPlaybackService(
                trackMapper,
                webDavConfigMapper,
                webDavClient,
                securityProperties,
                playbackTokenService,
                playbackControlService,
                playbackProperties,
                beanProvider(meterRegistry)
        );

        PlaybackSessionResponse response = service.createPlaybackSession(21L, "demo-user");

        assertEquals(21L, response.getTrackId().longValue());
        assertTrue(response.getSignedStreamPath().contains("/api/v1/tracks/21/stream?playbackToken="));
        assertEquals(8L, response.getRefreshBeforeExpirySeconds().longValue());
        verify(playbackControlService).markTrackStarted("demo-user", 21L);
        assertEquals(1.0D, meterRegistry.find("music.playback.sign.success").counter().count());
        assertTrue(meterRegistry.find("music.playback.sign.latency").timer().count() >= 1);
    }

    private ObjectProvider<MeterRegistry> beanProvider(MeterRegistry meterRegistry) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("meterRegistry", meterRegistry);
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }
}
