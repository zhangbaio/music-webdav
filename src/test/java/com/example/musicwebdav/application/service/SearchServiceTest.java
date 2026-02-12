package com.example.musicwebdav.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.config.AppSearchProperties;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.SearchMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class SearchServiceTest {

    @Test
    void searchSongsShouldUseQueryCacheAndRecordMetrics() {
        SearchMapper mapper = org.mockito.Mockito.mock(SearchMapper.class);
        TrackEntity row = new TrackEntity();
        row.setId(1L);
        row.setTitle("A");
        row.setArtist("B");
        row.setAlbum("C");
        row.setSourcePath("/music/a.flac");
        row.setDurationSec(180);
        row.setHasLyric(1);

        when(mapper.selectSongPageAll(anyInt(), eq(20), eq("hello")))
                .thenReturn(Collections.singletonList(row));
        when(mapper.countSongAll("hello")).thenReturn(1L);

        AppSearchProperties properties = new AppSearchProperties();
        properties.setCacheTtlMs(5000L);
        properties.setCacheMaxEntries(64);
        properties.setSlowQueryThresholdMs(800L);
        properties.setP95TargetMs(800L);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SearchService service = new SearchService(mapper, properties, beanProvider(meterRegistry));

        PageResponse<TrackResponse> first = service.searchSongs("hello", "all", 1, 20);
        PageResponse<TrackResponse> second = service.searchSongs("hello", "all", 1, 20);

        assertEquals(1L, first.getTotal());
        assertEquals(1L, second.getTotal());
        assertEquals("A", second.getRecords().get(0).getTitle());

        verify(mapper, times(1)).selectSongPageAll(0, 20, "hello");
        verify(mapper, times(1)).countSongAll("hello");

        assertNotNull(meterRegistry.find("music.search.api.latency").timers());
        long latencyCount = meterRegistry.find("music.search.api.latency").timers()
                .stream()
                .mapToLong(Timer::count)
                .sum();
        assertEquals(2L, latencyCount);
        assertEquals(1.0D, meterRegistry.find("music.search.cache.hit").counter().count());
        assertEquals(1.0D, meterRegistry.find("music.search.cache.miss").counter().count());
    }

    private ObjectProvider<MeterRegistry> beanProvider(MeterRegistry meterRegistry) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("meterRegistry", meterRegistry);
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }
}
