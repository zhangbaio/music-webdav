package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.FavoriteStatusResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistTrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import java.util.Collections;
import org.springframework.stereotype.Service;

@Service
public class FavoriteService {

    private final PlaylistService playlistService;
    private final PlaylistTrackMapper playlistTrackMapper;
    private final TrackMapper trackMapper;

    public FavoriteService(PlaylistService playlistService,
                           PlaylistTrackMapper playlistTrackMapper,
                           TrackMapper trackMapper) {
        this.playlistService = playlistService;
        this.playlistTrackMapper = playlistTrackMapper;
        this.trackMapper = trackMapper;
    }

    public FavoriteStatusResponse favorite(Long trackId) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在");
        }
        Long playlistId = playlistService.getOrCreateFavoritesPlaylistId();
        playlistService.addTracks(playlistId, Collections.singletonList(trackId));
        return new FavoriteStatusResponse(trackId, true);
    }

    public FavoriteStatusResponse unfavorite(Long trackId) {
        Long playlistId = playlistService.getOrCreateFavoritesPlaylistId();
        playlistService.removeTrack(playlistId, trackId);
        return new FavoriteStatusResponse(trackId, false);
    }

    public FavoriteStatusResponse status(Long trackId) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            return new FavoriteStatusResponse(trackId, false);
        }
        Long playlistId = playlistService.getOrCreateFavoritesPlaylistId();
        boolean favorite = playlistTrackMapper.countByPlaylistAndTrack(playlistId, trackId) > 0;
        return new FavoriteStatusResponse(trackId, favorite);
    }

    public PageResponse<TrackResponse> listFavoriteTracks(int pageNo, int pageSize) {
        return playlistService.listFavoriteTracks(pageNo, pageSize);
    }
}
