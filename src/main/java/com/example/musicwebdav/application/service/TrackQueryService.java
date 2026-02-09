package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackDetailResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TrackQueryService {

    private static final Set<String> SUPPORTED_SORT_BY = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("updated_at", "created_at", "title", "artist", "album", "year"))
    );

    private final TrackMapper trackMapper;

    public TrackQueryService(TrackMapper trackMapper) {
        this.trackMapper = trackMapper;
    }

    public PageResponse<TrackResponse> listTracks(int pageNo,
                                                  int pageSize,
                                                  String keyword,
                                                  String artist,
                                                  String album,
                                                  String genre,
                                                  Integer year,
                                                  String sortBy,
                                                  String sortOrder) {
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, Math.min(200, pageSize));
        int offset = (safePageNo - 1) * safePageSize;
        String normalizedSortBy = normalizeSortBy(sortBy);
        String normalizedSortOrder = normalizeSortOrder(sortOrder);
        List<TrackEntity> rows = trackMapper.selectPage(
                offset, safePageSize, keyword, artist, album, genre, year, normalizedSortBy, normalizedSortOrder);
        long total = trackMapper.count(keyword, artist, album, genre, year);
        return new PageResponse<>(rows.stream().map(this::toResponse).collect(Collectors.toList()), total, safePageNo, safePageSize);
    }

    public List<TrackResponse> searchTracks(String keyword, Integer limit) {
        String safeKeyword = keyword == null ? "" : keyword.trim();
        if (safeKeyword.isEmpty()) {
            return Collections.emptyList();
        }
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(100, limit));
        return trackMapper.search(safeKeyword, safeLimit).stream().map(this::toResponse).collect(Collectors.toList());
    }

    public TrackDetailResponse getTrack(Long id) {
        TrackEntity entity = trackMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        return toDetailResponse(entity);
    }

    private TrackResponse toResponse(TrackEntity entity) {
        return new TrackResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getArtist(),
                entity.getAlbum(),
                entity.getSourcePath(),
                entity.getDurationSec()
        );
    }

    private TrackDetailResponse toDetailResponse(TrackEntity entity) {
        return new TrackDetailResponse(
                entity.getId(),
                entity.getSourceConfigId(),
                entity.getSourcePath(),
                entity.getSourceEtag(),
                entity.getSourceLastModified(),
                entity.getSourceSize(),
                entity.getMimeType(),
                entity.getTitle(),
                entity.getArtist(),
                entity.getAlbum(),
                entity.getAlbumArtist(),
                entity.getTrackNo(),
                entity.getDiscNo(),
                entity.getYear(),
                entity.getGenre(),
                entity.getDurationSec(),
                entity.getBitrate(),
                entity.getSampleRate(),
                entity.getChannels(),
                entity.getHasCover(),
                entity.getCoverArtUrl(),
                entity.getUpdatedAt()
        );
    }

    private String normalizeSortBy(String sortBy) {
        if (sortBy == null) {
            return "updated_at";
        }
        String normalized = sortBy.trim().toLowerCase(Locale.ROOT);
        if ("updatedat".equals(normalized) || "updated_at".equals(normalized)) {
            return "updated_at";
        }
        if ("createdat".equals(normalized) || "created_at".equals(normalized)) {
            return "created_at";
        }
        return SUPPORTED_SORT_BY.contains(normalized) ? normalized : "updated_at";
    }

    private String normalizeSortOrder(String sortOrder) {
        if (sortOrder == null) {
            return "DESC";
        }
        return "ASC".equalsIgnoreCase(sortOrder.trim()) ? "ASC" : "DESC";
    }
}
