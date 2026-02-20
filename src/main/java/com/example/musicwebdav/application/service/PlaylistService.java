package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.AddPlaylistTracksResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.PlaylistCleanupResponse;
import com.example.musicwebdav.api.response.PlaylistResponse;
import com.example.musicwebdav.api.response.PlaylistTrackOperationResponse;
import com.example.musicwebdav.api.response.PlaylistTrackOrderResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistTrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistMapper;
import com.example.musicwebdav.api.response.AddPlaylistTracksResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.PlaylistCleanupResponse;
import com.example.musicwebdav.api.response.PlaylistResponse;
import com.example.musicwebdav.api.response.PlaylistTrackOperationResponse;
import com.example.musicwebdav.api.response.PlaylistTrackOrderResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistTrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistTrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.domain.model.SmartPlaylistRules;
import com.example.musicwebdav.common.security.SecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PlaylistService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistService.class);

    public static final String PLAYLIST_TYPE_NORMAL = "NORMAL";
    public static final String PLAYLIST_TYPE_SYSTEM = "SYSTEM";
    public static final String SYSTEM_CODE_FAVORITES = "FAVORITES";
    public static final String SYSTEM_NAME_FAVORITES = "Favorites";
    private static final int SORT_STEP = 10;

    private final PlaylistMapper playlistMapper;
    private final PlaylistTrackMapper playlistTrackMapper;
    private final TrackMapper trackMapper;
    private final ObjectMapper objectMapper;

    public PlaylistService(PlaylistMapper playlistMapper,
                           PlaylistTrackMapper playlistTrackMapper,
                           TrackMapper trackMapper,
                           ObjectMapper objectMapper) {
        this.playlistMapper = playlistMapper;
        this.playlistTrackMapper = playlistTrackMapper;
        this.trackMapper = trackMapper;
        this.objectMapper = objectMapper;
    }

    public List<PlaylistResponse> listPlaylists() {
        Long userId = SecurityUtil.getCurrentUserId();
        ensureFavoritesPlaylistExists(userId);
        return playlistMapper.selectAllActive(userId).stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public PlaylistResponse createPlaylist(String name) {
        Long userId = SecurityUtil.getCurrentUserId();
        String safeName = normalizePlaylistName(name);
        if (playlistMapper.countByName(userId, safeName) > 0) {
            throw new BusinessException("409", "歌单已存在");
        }
        Integer maxSortNo = playlistMapper.selectMaxSortNoOfNormal(userId);
        int sortNo = (maxSortNo == null ? 0 : maxSortNo) + SORT_STEP;
        PlaylistEntity entity = new PlaylistEntity();
        entity.setUserId(userId);
        entity.setName(safeName);
        entity.setPlaylistType(PLAYLIST_TYPE_NORMAL);
        entity.setSystemCode(null);
        entity.setSortNo(sortNo);
        entity.setIsDeleted(0);
        entity.setTrackCount(0);
        playlistMapper.insert(entity);
        PlaylistEntity created = playlistMapper.selectActiveById(entity.getId(), userId);
        return toResponse(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public PlaylistResponse renamePlaylist(Long playlistId, String name) {
        Long userId = SecurityUtil.getCurrentUserId();
        PlaylistEntity playlist = requirePlaylist(playlistId, userId);
        if (!PLAYLIST_TYPE_NORMAL.equals(playlist.getPlaylistType())) {
            throw new BusinessException("400", "系统歌单不支持重命名");
        }
        String safeName = normalizePlaylistName(name);
        if (!safeName.equals(playlist.getName()) && playlistMapper.countByName(userId, safeName) > 0) {
            throw new BusinessException("409", "歌单已存在");
        }
        int updated = playlistMapper.renameNormal(playlistId, userId, safeName);
        if (updated <= 0) {
            throw new BusinessException("404", "歌单不存在");
        }
        return toResponse(playlistMapper.selectActiveById(playlistId, userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePlaylist(Long playlistId) {
        Long userId = SecurityUtil.getCurrentUserId();
        PlaylistEntity playlist = requirePlaylist(playlistId, userId);
        if (!PLAYLIST_TYPE_NORMAL.equals(playlist.getPlaylistType())) {
            throw new BusinessException("400", "系统歌单不支持删除");
        }
        playlistTrackMapper.deleteByPlaylistId(playlistId);
        int updated = playlistMapper.softDeleteNormal(playlistId, userId);
        if (updated <= 0) {
            throw new BusinessException("404", "歌单不存在");
        }
    }

    public PageResponse<TrackResponse> listPlaylistTracks(Long playlistId, int pageNo, int pageSize) {
        Long userId = SecurityUtil.getCurrentUserId();
        requirePlaylist(playlistId, userId);
        return doListPlaylistTracks(playlistId, pageNo, pageSize, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AddPlaylistTracksResponse addTracks(Long playlistId, List<Long> trackIds) {
        Long userId = SecurityUtil.getCurrentUserId();
        requirePlaylist(playlistId, userId);
        List<Long> uniqueTrackIds = normalizeIdList(trackIds, "trackIds");

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
        PlaylistEntity playlist = playlistMapper.selectActiveById(playlistId, userId);
        int trackCount = playlist == null || playlist.getTrackCount() == null ? 0 : playlist.getTrackCount();
        return new AddPlaylistTracksResponse(
                playlistId,
                uniqueTrackIds.size(),
                addedCount,
                duplicateCount,
                trackCount
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean removeTrack(Long playlistId, Long trackId) {
        if (trackId == null) {
            return false;
        }
        PlaylistTrackOperationResponse response = removeTracks(playlistId, java.util.Collections.singletonList(trackId));
        return response.getAffectedCount() > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public PlaylistTrackOperationResponse removeTracks(Long playlistId, List<Long> trackIds) {
        Long userId = SecurityUtil.getCurrentUserId();
        requirePlaylist(playlistId, userId);
        List<Long> uniqueTrackIds = normalizeIdList(trackIds, "trackIds");
        int affected = playlistTrackMapper.batchDeleteByPlaylistAndTrackIds(playlistId, uniqueTrackIds);
        if (affected > 0) {
            normalizeTrackOrderNo(playlistId);
        }
        playlistMapper.refreshTrackCount(playlistId);
        PlaylistEntity playlist = playlistMapper.selectActiveById(playlistId, userId);
        int trackCount = playlist == null || playlist.getTrackCount() == null ? 0 : playlist.getTrackCount();
        return new PlaylistTrackOperationResponse(
                playlistId,
                uniqueTrackIds.size(),
                affected,
                Math.max(0, uniqueTrackIds.size() - affected),
                trackCount
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public List<PlaylistResponse> reorderPlaylists(List<Long> playlistIds) {
        Long userId = SecurityUtil.getCurrentUserId();
        ensureFavoritesPlaylistExists(userId);
        List<Long> orderedPlaylistIds = normalizeIdList(playlistIds, "playlistIds");
        List<PlaylistEntity> activePlaylists = playlistMapper.selectAllActive(userId);
        List<PlaylistEntity> normalPlaylists = new ArrayList<>();
        Map<Long, PlaylistEntity> normalById = new HashMap<>();
        for (PlaylistEntity entity : activePlaylists) {
            if (PLAYLIST_TYPE_NORMAL.equals(entity.getPlaylistType())) {
                normalPlaylists.add(entity);
                normalById.put(entity.getId(), entity);
            }
        }
        if (normalPlaylists.isEmpty()) {
            return listPlaylists();
        }

        Set<Long> requested = new LinkedHashSet<>();
        for (Long playlistId : orderedPlaylistIds) {
            PlaylistEntity target = normalById.get(playlistId);
            if (target == null) {
                throw new BusinessException("404", "普通歌单不存在，playlistId=" + playlistId);
            }
            requested.add(playlistId);
        }

        List<Long> finalOrder = new ArrayList<>(normalPlaylists.size());
        finalOrder.addAll(requested);
        for (PlaylistEntity entity : normalPlaylists) {
            if (!requested.contains(entity.getId())) {
                finalOrder.add(entity.getId());
            }
        }

        int sortNo = SORT_STEP;
        for (Long playlistId : finalOrder) {
            PlaylistEntity current = normalById.get(playlistId);
            if (current.getSortNo() == null || current.getSortNo() != sortNo) {
                playlistMapper.updateSortNoNormal(playlistId, userId, sortNo);
            }
            sortNo += SORT_STEP;
        }
        return listPlaylists();
    }

    @Transactional(rollbackFor = Exception.class)
    public PlaylistTrackOrderResponse reorderTracks(Long playlistId, List<Long> trackIds) {
        Long userId = SecurityUtil.getCurrentUserId();
        requirePlaylist(playlistId, userId);
        List<PlaylistTrackEntity> relations = playlistTrackMapper.selectByPlaylistIdOrdered(playlistId);
        if (relations.isEmpty()) {
            return new PlaylistTrackOrderResponse(playlistId, 0, 0);
        }
        List<Long> orderedTrackIds = normalizeIdList(trackIds, "trackIds");
        Map<Long, PlaylistTrackEntity> relationByTrackId = new HashMap<>(relations.size());
        for (PlaylistTrackEntity relation : relations) {
            relationByTrackId.put(relation.getTrackId(), relation);
        }

        Set<Long> requested = new LinkedHashSet<>();
        for (Long trackId : orderedTrackIds) {
            if (!relationByTrackId.containsKey(trackId)) {
                throw new BusinessException("404", "歌单中不存在该歌曲，trackId=" + trackId);
            }
            requested.add(trackId);
        }

        List<PlaylistTrackEntity> targetOrder = new ArrayList<>(relations.size());
        for (Long trackId : requested) {
            targetOrder.add(relationByTrackId.get(trackId));
        }
        for (PlaylistTrackEntity relation : relations) {
            if (!requested.contains(relation.getTrackId())) {
                targetOrder.add(relation);
            }
        }

        int updatedCount = 0;
        int orderNo = 1;
        for (PlaylistTrackEntity relation : targetOrder) {
            if (relation.getOrderNo() == null || relation.getOrderNo() != orderNo) {
                playlistTrackMapper.updateOrderNoById(relation.getId(), orderNo);
                updatedCount++;
            }
            orderNo++;
        }
        if (updatedCount > 0) {
            playlistMapper.touchUpdatedAt(playlistId);
        }
        return new PlaylistTrackOrderResponse(playlistId, relations.size(), updatedCount);
    }

    @Transactional(rollbackFor = Exception.class)
    public PlaylistCleanupResponse cleanupPlaylistData(boolean normalizeOrderNo) {
        LocalDateTime startedAt = LocalDateTime.now();
        int removedByDeletedPlaylist = playlistTrackMapper.deleteByDeletedPlaylists();
        int removedByDeletedTrack = playlistTrackMapper.deleteByDeletedTracks();

        int normalizedPlaylistCount = 0;
        if (normalizeOrderNo) {
            Long userId = SecurityUtil.getCurrentUserId();
            List<Long> playlistIds = playlistMapper.selectAllActiveIds(userId);
            for (Long playlistId : playlistIds) {
                int changed = normalizeTrackOrderNo(playlistId);
                if (changed > 0) {
                    normalizedPlaylistCount++;
                }
            }
        }

        int refreshedPlaylistCount = playlistMapper.refreshAllTrackCount();
        LocalDateTime finishedAt = LocalDateTime.now();
        return new PlaylistCleanupResponse(
                removedByDeletedPlaylist,
                removedByDeletedTrack,
                normalizedPlaylistCount,
                refreshedPlaylistCount,
                normalizeOrderNo,
                startedAt,
                finishedAt
        );
    }

    public Long getOrCreateFavoritesPlaylistId() {
        Long userId = SecurityUtil.getCurrentUserId();
        PlaylistEntity favorites = ensureFavoritesPlaylistExists(userId);
        return favorites.getId();
    }

    public PageResponse<TrackResponse> listFavoriteTracks(int pageNo, int pageSize) {
        Long userId = SecurityUtil.getCurrentUserId();
        Long playlistId = getOrCreateFavoritesPlaylistId();
        return doListPlaylistTracks(playlistId, pageNo, pageSize, userId);
    }

    private int normalizeTrackOrderNo(Long playlistId) {
        List<PlaylistTrackEntity> relations = playlistTrackMapper.selectByPlaylistIdOrdered(playlistId);
        int orderNo = 1;
        int updatedCount = 0;
        for (PlaylistTrackEntity relation : relations) {
            if (relation.getOrderNo() == null || relation.getOrderNo() != orderNo) {
                playlistTrackMapper.updateOrderNoById(relation.getId(), orderNo);
                updatedCount++;
            }
            orderNo++;
        }
        if (updatedCount > 0) {
            playlistMapper.touchUpdatedAt(playlistId);
        }
        return updatedCount;
    }

    private PlaylistEntity requirePlaylist(Long playlistId, Long userId) {
        PlaylistEntity playlist = playlistMapper.selectActiveById(playlistId, userId);
        if (playlist == null) {
            throw new BusinessException("404", "歌单不存在");
        }
        return playlist;
    }

    private PlaylistEntity ensureFavoritesPlaylistExists(Long userId) {
        PlaylistEntity playlist = playlistMapper.selectBySystemCode(userId, SYSTEM_CODE_FAVORITES);
        if (playlist != null) {
            return playlist;
        }
        playlistMapper.insertSystemIfAbsent(userId, SYSTEM_NAME_FAVORITES, SYSTEM_CODE_FAVORITES);
        playlist = playlistMapper.selectBySystemCode(userId, SYSTEM_CODE_FAVORITES);
        if (playlist == null) {
            throw new BusinessException("500", "初始化收藏歌单失败");
        }
        return playlist;
    }

    private PageResponse<TrackResponse> doListPlaylistTracks(Long playlistId, int pageNo, int pageSize, Long userId) {
        PlaylistEntity playlist = playlistMapper.selectActiveById(playlistId, userId);
        if (playlist != null && StringUtils.hasText(playlist.getRules())) {
            return listSmartPlaylistTracks(playlist, pageNo, pageSize, userId);
        }

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

    private PageResponse<TrackResponse> listSmartPlaylistTracks(PlaylistEntity playlist, int pageNo, int pageSize, Long userId) {
        SmartPlaylistRules rules;
        try {
            rules = objectMapper.readValue(playlist.getRules(), SmartPlaylistRules.class);
        } catch (Exception e) {
            log.error("Failed to parse smart playlist rules, id={}", playlist.getId(), e);
            return new PageResponse<>(new ArrayList<>(), 0, pageNo, pageSize);
        }

        int limit = rules.getLimit() != null ? rules.getLimit() : 100;
        List<TrackEntity> tracks = trackMapper.selectSmartTracks(
                limit,
                rules.getSortBy(),
                rules.getSortOrder(),
                rules.getGenre(),
                rules.getArtist()
        );

        // Smart playlists are currently not paginated in the DB query (they use limit),
        // so we manually paginate the result set if needed.
        int total = tracks.size();
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, Math.min(200, pageSize));
        int start = (safePageNo - 1) * safePageSize;
        List<TrackResponse> records = new ArrayList<>();
        if (start < total) {
            int end = Math.min(start + safePageSize, total);
            for (int i = start; i < end; i++) {
                records.add(toTrackResponse(tracks.get(i)));
            }
        }

        return new PageResponse<>(records, total, safePageNo, safePageSize);
    }

    private List<Long> normalizeIdList(List<Long> ids, String fieldName) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("400", fieldName + "不能为空");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BusinessException("400", fieldName + "不能为空");
        }
        return new ArrayList<>(uniqueIds);
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
                entity.getSortNo(),
                entity.getTrackCount(),
                entity.getRules(),
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
