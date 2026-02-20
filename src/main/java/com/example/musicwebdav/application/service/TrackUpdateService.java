package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.request.TrackUpdateRequest;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackUpdateService {

    private static final Logger log = LoggerFactory.getLogger(TrackUpdateService.class);

    private final TrackMapper trackMapper;

    public TrackUpdateService(TrackMapper trackMapper) {
        this.trackMapper = trackMapper;
    }

    @Transactional
    public TrackResponse updateTrack(Long id, TrackUpdateRequest request) {
        TrackEntity track = trackMapper.selectById(id);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在");
        }

        log.info("UPDATING_TRACK_METADATA id={} oldTitle={} newTitle={}", id, track.getTitle(), request.getTitle());

        if (request.getTitle() != null) track.setTitle(request.getTitle());
        if (request.getArtist() != null) track.setArtist(request.getArtist());
        if (request.getAlbum() != null) track.setAlbum(request.getAlbum());
        if (request.getAlbumArtist() != null) track.setAlbumArtist(request.getAlbumArtist());
        if (request.getTrackNo() != null) track.setTrackNo(request.getTrackNo());
        if (request.getDiscNo() != null) track.setDiscNo(request.getDiscNo());
        if (request.getYear() != null) track.setYear(request.getYear());
        if (request.getGenre() != null) track.setGenre(request.getGenre());

        trackMapper.updateMetadata(track);

        return toResponse(track);
    }

    private TrackResponse toResponse(TrackEntity entity) {
        return new TrackResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getArtist(),
                entity.getAlbum(),
                entity.getSourcePath(),
                entity.getDurationSec(),
                entity.getHasLyric()
        );
    }
}
