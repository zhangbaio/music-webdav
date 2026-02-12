package com.example.musicwebdav.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.musicwebdav.api.request.PlaybackControlRequest;
import com.example.musicwebdav.api.request.QueueReorderRequest;
import com.example.musicwebdav.api.response.NowPlayingStatusResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PlaybackControlServiceTest {

    private PlaybackControlService service;

    @BeforeEach
    void setUp() {
        TrackMapper trackMapper = mock(TrackMapper.class);
        when(trackMapper.selectById(1L)).thenReturn(track(1L, "Song-1"));
        when(trackMapper.selectById(2L)).thenReturn(track(2L, "Song-2"));
        when(trackMapper.selectById(3L)).thenReturn(track(3L, "Song-3"));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new PlaybackControlService(trackMapper, beanProvider(meterRegistry));
    }

    @Test
    void shouldPauseThenResumeFromPlaying() {
        service.markTrackStarted("u1", 1L);

        NowPlayingStatusResponse paused = service.handleControl("u1", request("pause", 1L, 10, 1L, 2L));
        assertEquals("paused", paused.getState());
        assertEquals(1L, paused.getCurrentTrackId().longValue());
        assertTrue(paused.isHasNext());

        NowPlayingStatusResponse resumed = service.handleControl("u1", request("resume", 1L, 10, 1L, 2L));
        assertEquals("playing", resumed.getState());
        assertEquals(1L, resumed.getCurrentTrackId().longValue());
        assertTrue(resumed.isHasNext());
    }

    @Test
    void shouldSwitchTrackOnNextAndPrevious() {
        service.markTrackStarted("u2", 1L);

        NowPlayingStatusResponse switchedNext = service.handleControl("u2", request("next", 1L, 18, 1L, 2L, 3L));
        assertEquals("playing", switchedNext.getState());
        assertEquals(2L, switchedNext.getCurrentTrackId().longValue());
        assertTrue(switchedNext.isHasPrevious());
        assertTrue(switchedNext.isHasNext());
        assertEquals(0, switchedNext.getProgressSec().intValue());

        NowPlayingStatusResponse switchedPrevious = service.handleControl("u2", request("previous", 2L, 9, 1L, 2L, 3L));
        assertEquals("playing", switchedPrevious.getState());
        assertEquals(1L, switchedPrevious.getCurrentTrackId().longValue());
        assertFalse(switchedPrevious.isHasPrevious());
        assertTrue(switchedPrevious.isHasNext());
    }

    @Test
    void shouldRejectTrackSwitchAtBoundary() {
        service.markTrackStarted("u3", 1L);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.handleControl("u3", request("previous", 1L, 0, 1L))
        );
        assertEquals("PLAYBACK_QUEUE_BOUNDARY", error.getCode());
    }

    @Test
    void shouldReorderQueueAndKeepCurrentTrack() {
        service.markTrackStarted("u5", 2L);

        NowPlayingStatusResponse reordered = service.reorderQueue("u5", reorderRequest(2, 0, 2L, null, 1L, 2L, 3L));
        assertEquals(3L, reordered.getQueueTrackIds().get(0).longValue());
        assertEquals(1L, reordered.getQueueTrackIds().get(1).longValue());
        assertEquals(2L, reordered.getQueueTrackIds().get(2).longValue());
        assertEquals(2L, reordered.getCurrentTrackId().longValue());
        assertTrue(reordered.isHasPrevious());
        assertFalse(reordered.isHasNext());
    }

    @Test
    void shouldRejectQueueReorderWhenVersionConflict() {
        service.markTrackStarted("u6", 1L);
        long version = service.getNowPlaying("u6").getUpdatedAtEpochSecond();

        service.reorderQueue("u6", reorderRequest(0, 1, 1L, version, 1L, 2L, 3L));
        BusinessException conflict = assertThrows(
                BusinessException.class,
                () -> service.reorderQueue("u6", reorderRequest(1, 0, 1L, version, 2L, 1L, 3L))
        );
        assertEquals("PLAYBACK_QUEUE_CONFLICT", conflict.getCode());
    }

    @Test
    void shouldReturnReadyWhenNowPlayingNotInitialized() {
        NowPlayingStatusResponse status = service.getNowPlaying("u4");

        assertEquals("ready", status.getState());
        assertNull(status.getCurrentTrackId());
        assertNull(status.getTrack());
        assertFalse(status.isHasPrevious());
        assertFalse(status.isHasNext());
    }

    private PlaybackControlRequest request(String command, Long currentTrackId, Integer progressSec, Long... queueTrackIds) {
        PlaybackControlRequest request = new PlaybackControlRequest();
        request.setCommand(command);
        request.setCurrentTrackId(currentTrackId);
        request.setProgressSec(progressSec);
        request.setQueueTrackIds(Arrays.asList(queueTrackIds));
        return request;
    }

    private QueueReorderRequest reorderRequest(int from,
                                               int to,
                                               Long currentTrackId,
                                               Long expectedVersion,
                                               Long... queueTrackIds) {
        QueueReorderRequest request = new QueueReorderRequest();
        request.setFromIndex(from);
        request.setToIndex(to);
        request.setCurrentTrackId(currentTrackId);
        request.setExpectedUpdatedAtEpochSecond(expectedVersion);
        request.setQueueTrackIds(Arrays.asList(queueTrackIds));
        request.setProgressSec(0);
        return request;
    }

    private TrackEntity track(Long id, String title) {
        TrackEntity entity = new TrackEntity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setArtist("Artist-" + id);
        entity.setAlbum("Album-" + id);
        entity.setDurationSec(200);
        return entity;
    }

    private ObjectProvider<MeterRegistry> beanProvider(MeterRegistry meterRegistry) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("meterRegistry", meterRegistry);
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }
}
