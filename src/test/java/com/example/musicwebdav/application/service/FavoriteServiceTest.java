package com.example.musicwebdav.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.musicwebdav.api.request.FavoriteSyncRequest;
import com.example.musicwebdav.api.response.FavoriteStatusResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistTrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class FavoriteServiceTest {

    private FavoriteService service;
    private PlaylistService playlistService;
    private PlaylistTrackMapper playlistTrackMapper;
    private TrackMapper trackMapper;

    @BeforeEach
    void setUp() {
        playlistService = mock(PlaylistService.class);
        playlistTrackMapper = mock(PlaylistTrackMapper.class);
        trackMapper = mock(TrackMapper.class);

        when(playlistService.getOrCreateFavoritesPlaylistId()).thenReturn(88L);
        when(playlistTrackMapper.countByPlaylistAndTrack(88L, 101L)).thenReturn(0);
        when(trackMapper.selectById(101L)).thenReturn(track(101L));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new FavoriteService(playlistService, playlistTrackMapper, trackMapper, beanProvider(meterRegistry));
    }

    @Test
    void syncFavoriteShouldBeIdempotentByKey() {
        FavoriteSyncRequest request = new FavoriteSyncRequest();
        request.setTargetFavorite(true);
        request.setIdempotencyKey("fav-1");

        FavoriteStatusResponse first = service.syncFavorite("demo", 101L, request, null);
        FavoriteStatusResponse second = service.syncFavorite("demo", 101L, request, null);

        assertEquals(true, first.getFavorite());
        assertEquals(first.getVersion(), second.getVersion());
        verify(playlistService, times(1)).addTracks(88L, Collections.singletonList(101L));
    }

    @Test
    void syncFavoriteShouldRejectConflictVersion() {
        FavoriteSyncRequest request = new FavoriteSyncRequest();
        request.setTargetFavorite(true);
        service.syncFavorite("demo", 101L, request, null);

        FavoriteSyncRequest conflictRequest = new FavoriteSyncRequest();
        conflictRequest.setTargetFavorite(false);
        conflictRequest.setExpectedVersion(0L);
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.syncFavorite("demo", 101L, conflictRequest, null)
        );

        assertEquals("FAVORITE_CONFLICT", error.getCode());
    }

    private TrackEntity track(Long id) {
        TrackEntity entity = new TrackEntity();
        entity.setId(id);
        entity.setTitle("Song-" + id);
        entity.setArtist("Artist-" + id);
        entity.setAlbum("Album-" + id);
        return entity;
    }

    private ObjectProvider<MeterRegistry> beanProvider(MeterRegistry meterRegistry) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("meterRegistry", meterRegistry);
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }
}
