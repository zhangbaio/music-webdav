package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.AddPlaylistTracksResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.PlaylistResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistTrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlaylistService {

    public static final String PLAYLIST_TYPE_NORMAL = "NORMAL";
    public static final String PLAYLIST_TYPE_SYSTEM = "SYSTEM";
    public static final String SYSTEM_CODE_FAVORITES = "FAVORITES";
    public static final String SYSTEM_NAME_FAVORITES = "Favorites";

    private final PlaylistMapper playlistMapper;
    private final PlaylistTrackMapper playlistTrackMapper;
    private final TrackMapper trackMapper;

    public PlaylistService(PlaylistMapper playlistMapper,
                           PlaylistTrackMapper playlistTrackMapper,
                           TrackMapper trackMapper) {
        this.playlistMapper = playlistMapper;
        this.playlistTrackMapper = playlistTrackMapper;
        this.trackMapper = trackMapper;
    }

    public List<PlaylistResponse> listPlaylists() {
        ensureFavoritesPlaylistExists();
        return playlistMapper.selectAllActive().stream().map(this::toResponse).collect(Collectors.toList());
    }

    public PlaylistResponse createPlaylist(String name) {
        String safeName = normalizePlaylistName(name);
        if (playlistMapper.countByName(safeName) > 0) {
            throw new BusinessException("409", "歌单已存在");
        }
        PlaylistEntity entity = new PlaylistEntity();
        entity.setName(safeName);
        entity.setPlaylistType(PLAYLIST_TYPE_NORMAL);
        entity.setSystemCode(null);
        entity.setIsDeleted(0);
        entity.setTrackCount(0);
        playlistMapper.insert(entity);
        PlaylistEntity created = playlistMapper.selectActiveById(entity.getId());
        return toResponse(created);
    }

    public PlaylistResponse renamePlaylist(Long playlistId, String name) {
        PlaylistEntity playlist = requirePlaylist(playlistId);
        if (!PLAYLIST_TYPE_NORMAL.equals(playlist.getPlaylistType())) {
            throw new BusinessException("400", "系统歌单不支持重命名");
        }
        String safeName = normalizePlaylistName(name);
        if (!safeName.equals(playlist.getName()) && playlistMapper.countByName(safeName) > 0) {
            throw new BusinessException("409", "歌单已存在");
        }
        int updated = playlistMapper.renameNormal(playlistId, safeName);
        if (updated <= 0) {
            throw new BusinessException("404", "歌单不存在");
        }
        return toResponse(playlistMapper.selectActiveById(playlistId));
    }

    public void deletePlaylist(Long playlistId) {
        PlaylistEntity playlist = requirePlaylist(playlistId);
        if (!PLAYLIST_TYPE_NORMAL.equals(playlist.getPlaylistType())) {
            throw new BusinessException("400", "系统歌单不支持删除");
        }
        playlistTrackMapper.deleteByPlaylistId(playlistId);
        int updated = playlistMapper.softDeleteNormal(playlistId);
        if (updated <= 0) {
            throw new BusinessException("404", "歌单不存在");
        }
    }

    public PageResponse<TrackResponse> listPlaylistTracks(Long playlistId, int pageNo, int pageSize) {
        requirePlaylist(playlistId);
        return doListPlaylistTracks(playlistId, pageNo, pageSize);
    }

    public AddPlaylistTracksResponse addTracks(Long playlistId, List<Long> trackIds) {
        requirePlaylist(playlistId);
        if (trackIds == null || trackIds.isEmpty()) {
            throw new BusinessException("400", "trackIds不能为空");
        }

        Set<Long> uniqueTrackIds = new LinkedHashSet<>();
        for (Long trackId : trackIds) {
            if (trackId != null) {
                uniqueTrackIds.add(trackId);
            }
        }
        if (uniqueTrackIds.isEmpty()) {
            throw new BusinessException("400", "trackIds不能为空");
        }

        Integer maxOrderNo = playlistTrackMapper.selectMaxOrderNo(playlistId);
        int nextOrder = maxOrderNo == null ? 0 : maxOrderNo;
        int addedCount = 0;
        int duplicateCount = 0;
        for (Long trackId : uniqueTrackIds) {
            TrackEntity track = trackMapper.selectById(trackId);
            if (track == null) {
                throw new BusinessException("404", "歌曲不存在，trackId=" + trackId);
            }
            int exists = playlistTrackMapper.countByPlaylistAndTrack(playlistId, trackId);
            if (exists > 0) {
                duplicateCount++;
                continue;
            }
            playlistTrackMapper.upsert(playlistId, trackId, ++nextOrder);
            addedCount++;
        }

        playlistMapper.refreshTrackCount(playlistId);
        PlaylistEntity playlist = playlistMapper.selectActiveById(playlistId);
        int trackCount = playlist == null || playlist.getTrackCount() == null ? 0 : playlist.getTrackCount();
        return new AddPlaylistTracksResponse(
                playlistId,
                uniqueTrackIds.size(),
                addedCount,
                duplicateCount,
                trackCount
        );
    }

    public boolean removeTrack(Long playlistId, Long trackId) {
        requirePlaylist(playlistId);
        int affected = playlistTrackMapper.deleteByPlaylistAndTrack(playlistId, trackId);
        playlistMapper.refreshTrackCount(playlistId);
        return affected > 0;
    }

    public Long getOrCreateFavoritesPlaylistId() {
        PlaylistEntity favorites = ensureFavoritesPlaylistExists();
        return favorites.getId();
    }

    public PageResponse<TrackResponse> listFavoriteTracks(int pageNo, int pageSize) {
        Long playlistId = getOrCreateFavoritesPlaylistId();
        return doListPlaylistTracks(playlistId, pageNo, pageSize);
    }

    private PlaylistEntity requirePlaylist(Long playlistId) {
        PlaylistEntity playlist = playlistMapper.selectActiveById(playlistId);
        if (playlist == null) {
            throw new BusinessException("404", "歌单不存在");
        }
        return playlist;
    }

    private PlaylistEntity ensureFavoritesPlaylistExists() {
        PlaylistEntity playlist = playlistMapper.selectBySystemCode(SYSTEM_CODE_FAVORITES);
        if (playlist != null) {
            return playlist;
        }
        playlistMapper.insertSystemIfAbsent(SYSTEM_NAME_FAVORITES, SYSTEM_CODE_FAVORITES);
        playlist = playlistMapper.selectBySystemCode(SYSTEM_CODE_FAVORITES);
        if (playlist == null) {
            throw new BusinessException("500", "初始化收藏歌单失败");
        }
        return playlist;
    }

    private PageResponse<TrackResponse> doListPlaylistTracks(Long playlistId, int pageNo, int pageSize) {
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, Math.min(200, pageSize));
        int offset = (safePageNo - 1) * safePageSize;
        List<TrackEntity> rows = playlistTrackMapper.selectTrackPage(playlistId, offset, safePageSize);
        long total = playlistTrackMapper.countTracks(playlistId);

        List<TrackResponse> records = new ArrayList<>(rows.size());
        for (TrackEntity row : rows) {
            records.add(toTrackResponse(row));
        }
        return new PageResponse<>(records, total, safePageNo, safePageSize);
    }

    private String normalizePlaylistName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            throw new BusinessException("400", "歌单名称不能为空");
        }
        String safeName = rawName.trim();
        if (safeName.length() > 128) {
            throw new BusinessException("400", "歌单名称长度不能超过128");
        }
        return safeName;
    }

    private PlaylistResponse toResponse(PlaylistEntity entity) {
        return new PlaylistResponse(
                entity.getId(),
                entity.getName(),
                entity.getPlaylistType(),
                entity.getSystemCode(),
                entity.getTrackCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
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
}
