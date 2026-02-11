package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.AlbumSearchResponse;
import com.example.musicwebdav.api.response.ArtistSearchResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.SearchClassifyResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.SearchMapper;
import com.example.musicwebdav.infrastructure.persistence.model.SearchAlbumRow;
import com.example.musicwebdav.infrastructure.persistence.model.SearchArtistRow;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SearchService {

    private static final String MODE_SONG = "SONG";
    private static final String MODE_ARTIST = "ARTIST";

    private final SearchMapper searchMapper;

    public SearchService(SearchMapper searchMapper) {
        this.searchMapper = searchMapper;
    }

    public SearchClassifyResponse classify(String keyword) {
        String safeKeyword = normalizeKeyword(keyword);
        if (!StringUtils.hasText(safeKeyword)) {
            return new SearchClassifyResponse(MODE_SONG, 1.0D, null, 0L, 0L, 0L, 0L, 0L);
        }

        long allSongCount = searchMapper.countSongAll(safeKeyword);
        long mineSongCount = searchMapper.countSongMine(safeKeyword);
        long artistCount = searchMapper.countArtists(safeKeyword);
        long albumCount = searchMapper.countAlbums(null, safeKeyword);

        String exactArtist = searchMapper.selectExactArtist(safeKeyword);
        String fuzzyArtist = StringUtils.hasText(exactArtist) ? null : searchMapper.selectFuzzyArtist(safeKeyword);
        String inferredArtist = StringUtils.hasText(exactArtist) ? exactArtist : fuzzyArtist;
        long artistSongCount = StringUtils.hasText(inferredArtist)
                ? searchMapper.countArtistTracksByName(inferredArtist)
                : 0L;

        String mode = MODE_SONG;
        double confidence = 0.86D;
        if (StringUtils.hasText(exactArtist)) {
            mode = MODE_ARTIST;
            confidence = 0.96D;
        } else if (shouldPreferArtistMode(safeKeyword, allSongCount, artistCount, artistSongCount)) {
            mode = MODE_ARTIST;
            confidence = 0.73D;
        }

        String normalizedArtist = MODE_ARTIST.equals(mode) ? inferredArtist : null;
        return new SearchClassifyResponse(
                mode,
                confidence,
                normalizedArtist,
                allSongCount,
                mineSongCount,
                artistCount,
                albumCount,
                artistSongCount
        );
    }

    public PageResponse<TrackResponse> searchSongs(String keyword, String scope, int pageNo, int pageSize) {
        String safeKeyword = normalizeKeyword(keyword);
        if (!StringUtils.hasText(safeKeyword)) {
            return emptyPage(pageNo, pageSize);
        }
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        int offset = (safePageNo - 1) * safePageSize;
        boolean mineScope = "mine".equalsIgnoreCase(scope);

        List<TrackEntity> rows = mineScope
                ? searchMapper.selectSongPageMine(offset, safePageSize, safeKeyword)
                : searchMapper.selectSongPageAll(offset, safePageSize, safeKeyword);
        long total = mineScope ? searchMapper.countSongMine(safeKeyword) : searchMapper.countSongAll(safeKeyword);
        return new PageResponse<>(
                rows.stream().map(this::toTrackResponse).collect(Collectors.toList()),
                total,
                safePageNo,
                safePageSize
        );
    }

    public PageResponse<ArtistSearchResponse> searchArtists(String keyword, int pageNo, int pageSize) {
        String safeKeyword = normalizeKeyword(keyword);
        if (!StringUtils.hasText(safeKeyword)) {
            return new PageResponse<>(Collections.emptyList(), 0L, normalizePageNo(pageNo), normalizePageSize(pageSize));
        }
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        int offset = (safePageNo - 1) * safePageSize;
        List<SearchArtistRow> rows = searchMapper.selectArtistPage(offset, safePageSize, safeKeyword);
        long total = searchMapper.countArtists(safeKeyword);
        return new PageResponse<>(
                rows.stream().map(this::toArtistResponse).collect(Collectors.toList()),
                total,
                safePageNo,
                safePageSize
        );
    }

    public PageResponse<AlbumSearchResponse> searchAlbums(String artist, String keyword, int pageNo, int pageSize) {
        String safeArtist = normalizeKeyword(artist);
        String safeKeyword = normalizeKeyword(keyword);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        if (!StringUtils.hasText(safeArtist) && !StringUtils.hasText(safeKeyword)) {
            return new PageResponse<>(Collections.emptyList(), 0L, safePageNo, safePageSize);
        }
        int offset = (safePageNo - 1) * safePageSize;
        List<SearchAlbumRow> rows = searchMapper.selectAlbumPage(safeArtist, offset, safePageSize, safeKeyword);
        long total = searchMapper.countAlbums(safeArtist, safeKeyword);
        return new PageResponse<>(
                rows.stream().map(this::toAlbumResponse).collect(Collectors.toList()),
                total,
                safePageNo,
                safePageSize
        );
    }

    public PageResponse<TrackResponse> searchArtistTracks(String artist, String keyword, int pageNo, int pageSize) {
        String safeArtist = normalizeKeyword(artist);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        if (!StringUtils.hasText(safeArtist)) {
            return new PageResponse<>(Collections.emptyList(), 0L, safePageNo, safePageSize);
        }
        int offset = (safePageNo - 1) * safePageSize;
        String safeKeyword = normalizeKeyword(keyword);
        List<TrackEntity> rows = searchMapper.selectArtistTrackPage(safeArtist, offset, safePageSize, safeKeyword);
        long total = searchMapper.countArtistTracks(safeArtist, safeKeyword);
        return new PageResponse<>(
                rows.stream().map(this::toTrackResponse).collect(Collectors.toList()),
                total,
                safePageNo,
                safePageSize
        );
    }

    private boolean shouldPreferArtistMode(String keyword, long allSongCount, long artistCount, long artistSongCount) {
        if (!StringUtils.hasText(keyword) || keyword.length() < 2) {
            return false;
        }
        if (artistCount <= 0 || artistSongCount <= 0) {
            return false;
        }
        if (allSongCount <= 0) {
            return true;
        }
        if (artistCount == 1 && artistSongCount >= 2) {
            return true;
        }
        return artistCount <= 3 && artistSongCount * 2 >= allSongCount;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int normalizePageNo(int pageNo) {
        return Math.max(1, pageNo);
    }

    private int normalizePageSize(int pageSize) {
        return Math.max(1, Math.min(200, pageSize));
    }

    private PageResponse<TrackResponse> emptyPage(int pageNo, int pageSize) {
        return new PageResponse<>(Collections.emptyList(), 0L, normalizePageNo(pageNo), normalizePageSize(pageSize));
    }

    private TrackResponse toTrackResponse(TrackEntity entity) {
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

    private ArtistSearchResponse toArtistResponse(SearchArtistRow row) {
        return new ArtistSearchResponse(row.getArtist(), row.getTrackCount(), row.getCoverTrackId());
    }

    private AlbumSearchResponse toAlbumResponse(SearchAlbumRow row) {
        return new AlbumSearchResponse(row.getAlbum(), row.getArtist(), row.getTrackCount(), row.getCoverTrackId());
    }
}
