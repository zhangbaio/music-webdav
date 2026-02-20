package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.domain.PlayEventType;
import com.example.musicwebdav.infrastructure.persistence.entity.PlayEventEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlayEventMapper;
import com.example.musicwebdav.common.security.SecurityUtil;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlayEventService {

    private static final Logger log = LoggerFactory.getLogger(PlayEventService.class);

    private final PlayEventMapper playEventMapper;

    public PlayEventService(PlayEventMapper playEventMapper) {
        this.playEventMapper = playEventMapper;
    }

    /**
     * Record a play event for the given track.
     *
     * @param trackId     the track being played
     * @param eventType   PLAY_START, PLAY_COMPLETE, or SKIP
     * @param durationSec actual seconds played before this event
     */
    public void recordEvent(long trackId, PlayEventType eventType, int durationSec) {
        if (trackId <= 0) {
            log.warn("Ignored play event with invalid trackId={}", trackId);
            return;
        }

        Long userId = SecurityUtil.getCurrentUserId();
        PlayEventEntity entity = new PlayEventEntity();
        entity.setTrackId(trackId);
        entity.setUserId(userId);
        entity.setEventType(eventType.name());
        entity.setDurationSec(Math.max(0, durationSec));

        playEventMapper.insert(entity);

        log.debug("Recorded play event: userId={}, trackId={}, type={}, durationSec={}",
                userId, trackId, eventType, durationSec);
    }

    public PageResponse<TrackResponse> listRecentlyPlayedTracks(int pageNo, int pageSize) {
        Long userId = SecurityUtil.getCurrentUserId();
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, Math.min(200, pageSize));
        int offset = (safePageNo - 1) * safePageSize;

        List<TrackEntity> entities = playEventMapper.selectRecentlyPlayedTracks(offset, safePageSize, userId);
        List<TrackResponse> records = new ArrayList<>(entities.size());
        for (TrackEntity e : entities) {
            records.add(new TrackResponse(
                    e.getId(),
                    e.getTitle(),
                    e.getArtist(),
                    e.getAlbum(),
                    e.getSourcePath(),
                    e.getDurationSec(),
                    e.getHasLyric()
            ));
        }

        long total = playEventMapper.countRecentlyPlayedTracks(userId);
        return new PageResponse<>(records, total, safePageNo, safePageSize);
    }
}
