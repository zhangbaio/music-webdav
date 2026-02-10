package com.example.musicwebdav.application.job;

import com.example.musicwebdav.api.response.PlaylistCleanupResponse;
import com.example.musicwebdav.application.service.PlaylistService;
import com.example.musicwebdav.common.config.AppPlaylistProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PlaylistCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PlaylistCleanupJob.class);

    private final PlaylistService playlistService;
    private final AppPlaylistProperties appPlaylistProperties;

    public PlaylistCleanupJob(PlaylistService playlistService, AppPlaylistProperties appPlaylistProperties) {
        this.playlistService = playlistService;
        this.appPlaylistProperties = appPlaylistProperties;
    }

    @Scheduled(cron = "${app.playlist.cleanup-cron:0 30 4 * * ?}")
    public void cleanup() {
        if (!appPlaylistProperties.isCleanupEnabled()) {
            return;
        }
        try {
            PlaylistCleanupResponse response =
                    playlistService.cleanupPlaylistData(appPlaylistProperties.isCleanupNormalizeOrderNo());
            log.info("Playlist cleanup finished: {}", response);
        } catch (Exception e) {
            log.error("Playlist cleanup failed", e);
        }
    }
}
