package com.example.musicwebdav.application.service;

import com.example.musicwebdav.domain.model.DuplicateGroup;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DuplicateFilterService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateFilterService.class);

    private final TrackMapper trackMapper;

    public DuplicateFilterService(TrackMapper trackMapper) {
        this.trackMapper = trackMapper;
    }

    /**
     * Deduplicate tracks by (title, artist). Keep the one with the largest file size.
     * Returns the number of tracks marked as deleted.
     */
    public int deduplicateTracks(Long configId) {
        List<DuplicateGroup> groups = trackMapper.selectDuplicateGroups(configId);
        if (groups == null || groups.isEmpty()) {
            return 0;
        }
        log.info("DEDUP_START configId={} duplicateGroups={}", configId, groups.size());

        int totalDeduped = 0;
        for (DuplicateGroup group : groups) {
            List<TrackEntity> tracks = trackMapper.selectByNormalizedTitleAndArtist(
                    configId, group.getNormalizedTitle(), group.getNormalizedArtist());
            if (tracks == null || tracks.size() <= 1) {
                continue;
            }

            // Sort by source_size descending, keep the first (largest)
            Collections.sort(tracks, new Comparator<TrackEntity>() {
                @Override
                public int compare(TrackEntity a, TrackEntity b) {
                    long sizeA = a.getSourceSize() == null ? 0 : a.getSourceSize();
                    long sizeB = b.getSourceSize() == null ? 0 : b.getSourceSize();
                    return Long.compare(sizeB, sizeA);
                }
            });

            List<Long> idsToDelete = new ArrayList<>();
            for (int i = 1; i < tracks.size(); i++) {
                idsToDelete.add(tracks.get(i).getId());
            }

            if (!idsToDelete.isEmpty()) {
                int deleted = trackMapper.softDeleteByIds(idsToDelete);
                totalDeduped += deleted;
                log.debug("DEDUP_GROUP title='{}' artist='{}' kept={} deleted={}",
                        group.getNormalizedTitle(), group.getNormalizedArtist(),
                        tracks.get(0).getId(), idsToDelete);
            }
        }
        log.info("DEDUP_FINISH configId={} totalDeduped={}", configId, totalDeduped);
        return totalDeduped;
    }
}
