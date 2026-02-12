package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.AlbumSearchResponse;
import com.example.musicwebdav.api.response.ArtistSearchResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.SearchClassifyResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.config.AppSearchProperties;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.SearchMapper;
import com.example.musicwebdav.infrastructure.persistence.model.SearchAlbumRow;
import com.example.musicwebdav.infrastructure.persistence.model.SearchArtistRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final String MODE_SONG = "SONG";
    private static final String MODE_ARTIST = "ARTIST";

    private final SearchMapper searchMapper;
    private final AppSearchProperties appSearchProperties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, CacheEntry> queryCache = new ConcurrentHashMap<>();

    public SearchService(SearchMapper searchMapper,
                         AppSearchProperties appSearchProperties,
                         ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.searchMapper = searchMapper;
        this.appSearchProperties = appSearchProperties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public SearchClassifyResponse classify(String keyword) {
        String safeKeyword = normalizeKeyword(keyword);
        long startedAtNanos = System.nanoTime();
        String cacheState = "miss";
        boolean success = false;
        try {
            CacheLookup<SearchClassifyResponse> lookup = getOrLoadCache(
                    cacheKey("classify", safeKeyword),
                    () -> doClassify(safeKeyword)
            );
            cacheState = lookup.cacheHit ? "hit" : "miss";
            success = true;
            return lookup.value;
        } finally {
            recordSearchMetrics("classify", summarizeKeyword(safeKeyword), startedAtNanos, cacheState, success);
        }
    }

    public PageResponse<TrackResponse> searchSongs(String keyword, String scope, int pageNo, int pageSize) {
        String safeKeyword = normalizeKeyword(keyword);
        String safeScope = normalizeScope(scope);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        long startedAtNanos = System.nanoTime();
        String cacheState = "miss";
        boolean success = false;
        try {
            CacheLookup<PageResponse<TrackResponse>> lookup = getOrLoadCache(
                    cacheKey("songs", safeKeyword, safeScope, String.valueOf(safePageNo), String.valueOf(safePageSize)),
                    () -> doSearchSongs(safeKeyword, safeScope, safePageNo, safePageSize)
            );
            cacheState = lookup.cacheHit ? "hit" : "miss";
            success = true;
            return lookup.value;
        } finally {
            recordSearchMetrics(
                    "songs",
                    summarizeKeyword(safeKeyword) + ",scope=" + safeScope + ",page=" + safePageNo + ",size=" + safePageSize,
                    startedAtNanos,
                    cacheState,
                    success
            );
        }
    }

    public PageResponse<ArtistSearchResponse> searchArtists(String keyword, int pageNo, int pageSize) {
        String safeKeyword = normalizeKeyword(keyword);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        long startedAtNanos = System.nanoTime();
        String cacheState = "miss";
        boolean success = false;
        try {
            CacheLookup<PageResponse<ArtistSearchResponse>> lookup = getOrLoadCache(
                    cacheKey("artists", safeKeyword, String.valueOf(safePageNo), String.valueOf(safePageSize)),
                    () -> doSearchArtists(safeKeyword, safePageNo, safePageSize)
            );
            cacheState = lookup.cacheHit ? "hit" : "miss";
            success = true;
            return lookup.value;
        } finally {
            recordSearchMetrics(
                    "artists",
                    summarizeKeyword(safeKeyword) + ",page=" + safePageNo + ",size=" + safePageSize,
                    startedAtNanos,
                    cacheState,
                    success
            );
        }
    }

    public PageResponse<AlbumSearchResponse> searchAlbums(String artist, String keyword, int pageNo, int pageSize) {
        String safeArtist = normalizeKeyword(artist);
        String safeKeyword = normalizeKeyword(keyword);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        long startedAtNanos = System.nanoTime();
        String cacheState = "miss";
        boolean success = false;
        try {
            CacheLookup<PageResponse<AlbumSearchResponse>> lookup = getOrLoadCache(
                    cacheKey("albums", safeArtist, safeKeyword, String.valueOf(safePageNo), String.valueOf(safePageSize)),
                    () -> doSearchAlbums(safeArtist, safeKeyword, safePageNo, safePageSize)
            );
            cacheState = lookup.cacheHit ? "hit" : "miss";
            success = true;
            return lookup.value;
        } finally {
            recordSearchMetrics(
                    "albums",
                    "artist=" + summarizeKeyword(safeArtist) + ",keyword=" + summarizeKeyword(safeKeyword)
                            + ",page=" + safePageNo + ",size=" + safePageSize,
                    startedAtNanos,
                    cacheState,
                    success
            );
        }
    }

    public PageResponse<TrackResponse> searchArtistTracks(String artist, String keyword, int pageNo, int pageSize) {
        String safeArtist = normalizeKeyword(artist);
        String safeKeyword = normalizeKeyword(keyword);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        long startedAtNanos = System.nanoTime();
        String cacheState = "miss";
        boolean success = false;
        try {
            CacheLookup<PageResponse<TrackResponse>> lookup = getOrLoadCache(
                    cacheKey("artist-tracks", safeArtist, safeKeyword, String.valueOf(safePageNo), String.valueOf(safePageSize)),
                    () -> doSearchArtistTracks(safeArtist, safeKeyword, safePageNo, safePageSize)
            );
            cacheState = lookup.cacheHit ? "hit" : "miss";
            success = true;
            return lookup.value;
        } finally {
            recordSearchMetrics(
                    "artist-tracks",
                    "artist=" + summarizeKeyword(safeArtist) + ",keyword=" + summarizeKeyword(safeKeyword)
                            + ",page=" + safePageNo + ",size=" + safePageSize,
                    startedAtNanos,
                    cacheState,
                    success
            );
        }
    }

    private SearchClassifyResponse doClassify(String safeKeyword) {
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

    private PageResponse<TrackResponse> doSearchSongs(String safeKeyword, String safeScope, int safePageNo, int safePageSize) {
        if (!StringUtils.hasText(safeKeyword)) {
            return emptyPage(safePageNo, safePageSize);
        }
        int offset = (safePageNo - 1) * safePageSize;
        boolean mineScope = "mine".equalsIgnoreCase(safeScope);

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

    private PageResponse<ArtistSearchResponse> doSearchArtists(String safeKeyword, int safePageNo, int safePageSize) {
        if (!StringUtils.hasText(safeKeyword)) {
            return new PageResponse<>(Collections.emptyList(), 0L, safePageNo, safePageSize);
        }
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

    private PageResponse<AlbumSearchResponse> doSearchAlbums(
            String safeArtist, String safeKeyword, int safePageNo, int safePageSize) {
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

    private PageResponse<TrackResponse> doSearchArtistTracks(
            String safeArtist, String safeKeyword, int safePageNo, int safePageSize) {
        if (!StringUtils.hasText(safeArtist)) {
            return new PageResponse<>(Collections.emptyList(), 0L, safePageNo, safePageSize);
        }
        int offset = (safePageNo - 1) * safePageSize;
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

    @SuppressWarnings("unchecked")
    private <T> CacheLookup<T> getOrLoadCache(String cacheKey, Supplier<T> supplier) {
        if (!isCacheEnabled()) {
            return new CacheLookup<>(supplier.get(), false);
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = queryCache.get(cacheKey);
        if (cached != null && cached.expireAtMs > now) {
            incrementCounter("music.search.cache.hit", 1, "scope", "query");
            return new CacheLookup<>((T) cached.value, true);
        }
        if (cached != null) {
            queryCache.remove(cacheKey);
        }

        T value = supplier.get();
        long ttlMs = Math.max(1L, appSearchProperties.getCacheTtlMs());
        queryCache.put(cacheKey, new CacheEntry(value, now + ttlMs, now));
        incrementCounter("music.search.cache.miss", 1, "scope", "query");
        evictCacheIfNeeded();
        return new CacheLookup<>(value, false);
    }

    private void recordSearchMetrics(
            String endpoint, String queryPattern, long startedAtNanos, String cacheState, boolean success) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        recordDuration("music.search.api.latency", elapsedNanos,
                "endpoint", endpoint,
                "cache", cacheState,
                "success", success ? "true" : "false");

        long slowQueryThresholdMs = Math.max(1L, appSearchProperties.getSlowQueryThresholdMs());
        long p95TargetMs = Math.max(1L, appSearchProperties.getP95TargetMs());
        if (!"hit".equals(cacheState) && elapsedMs > slowQueryThresholdMs) {
            incrementCounter("music.search.api.slow", 1, "endpoint", endpoint);
            log.warn("SEARCH_SLOW_QUERY endpoint={} latencyMs={} thresholdMs={} p95TargetMs={} queryPattern={} traceId={}",
                    endpoint, elapsedMs, slowQueryThresholdMs, p95TargetMs, queryPattern, currentTraceId());
        }
    }

    private void evictCacheIfNeeded() {
        int maxEntries = Math.max(1, appSearchProperties.getCacheMaxEntries());
        if (queryCache.size() <= maxEntries) {
            return;
        }
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(queryCache.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(a.getValue().createdAtMs, b.getValue().createdAtMs));
        int removeCount = queryCache.size() - maxEntries;
        for (int i = 0; i < removeCount && i < entries.size(); i++) {
            queryCache.remove(entries.get(i).getKey());
        }
    }

    private boolean isCacheEnabled() {
        return appSearchProperties.getCacheTtlMs() > 0 && appSearchProperties.getCacheMaxEntries() > 0;
    }

    private String cacheKey(String endpoint, String... fields) {
        StringBuilder builder = new StringBuilder(endpoint);
        for (String field : fields) {
            builder.append('|');
            builder.append(field == null ? "null" : field);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private String summarizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "empty";
        }
        String safe = keyword.trim();
        return "len=" + safe.length() + ",hash=" + Integer.toHexString(safe.toLowerCase(Locale.ROOT).hashCode());
    }

    private String normalizeScope(String scope) {
        if ("mine".equalsIgnoreCase(scope)) {
            return "mine";
        }
        return "all";
    }

    private String currentTraceId() {
        String traceId = MDC.get("requestId");
        return StringUtils.hasText(traceId) ? traceId : "unknown";
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

    private void incrementCounter(String name, double value, String... tags) {
        if (meterRegistry == null || value <= 0) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment(value);
        } catch (Exception ex) {
            log.debug("Metric counter update failed, name={}", name, ex);
        }
    }

    private void recordDuration(String name, long nanos, String... tags) {
        if (meterRegistry == null || nanos <= 0) {
            return;
        }
        try {
            meterRegistry.timer(name, tags).record(nanos, TimeUnit.NANOSECONDS);
        } catch (Exception ex) {
            log.debug("Metric timer update failed, name={}", name, ex);
        }
    }

    private static final class CacheEntry {
        private final Object value;
        private final long expireAtMs;
        private final long createdAtMs;

        private CacheEntry(Object value, long expireAtMs, long createdAtMs) {
            this.value = value;
            this.expireAtMs = expireAtMs;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class CacheLookup<T> {
        private final T value;
        private final boolean cacheHit;

        private CacheLookup(T value, boolean cacheHit) {
            this.value = value;
            this.cacheHit = cacheHit;
        }
    }
}
