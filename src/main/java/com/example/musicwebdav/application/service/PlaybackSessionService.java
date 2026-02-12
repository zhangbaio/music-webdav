package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.PlaybackSignUtil;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import org.springframework.stereotype.Service;

/**
 * Generates time-limited signed playback URLs for tracks.
 * <p>
 * The signed URL points to the {@code /stream-signed} endpoint which
 * verifies the HMAC signature and proxies the audio stream from WebDAV
 * with Range support, without requiring a Bearer token.
 */
@Service
public class PlaybackSessionService {

    private final TrackMapper trackMapper;
    private final AppSecurityProperties securityProperties;

    public PlaybackSessionService(TrackMapper trackMapper,
                                  AppSecurityProperties securityProperties) {
        this.trackMapper = trackMapper;
        this.securityProperties = securityProperties;
    }

    /**
     * Create a signed playback session for a track.
     *
     * @param trackId the track to create a session for
     * @return PlaybackSessionGrant with the signed stream path
     */
    public PlaybackSessionGrant createSession(Long trackId) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null || (track.getIsDeleted() != null && track.getIsDeleted() == 1)) {
            throw new BusinessException("404", "歌曲不存在");
        }

        long now = System.currentTimeMillis() / 1000;
        int ttl = securityProperties.getPlaybackSignTtlSec();
        long expire = now + ttl;

        String signature = PlaybackSignUtil.sign(
                securityProperties.getPlaybackSignKey(), trackId, expire);

        String signedPath = "/api/v1/tracks/" + trackId
                + "/stream-signed?expire=" + expire + "&sign=" + signature;

        // Recommend client to refresh 5 minutes before expiry, or half ttl for short ttls
        int refreshBefore = Math.min(300, ttl / 2);

        PlaybackSessionGrant grant = new PlaybackSessionGrant();
        grant.setTrackId(trackId);
        grant.setSignedStreamPath(signedPath);
        grant.setIssuedAtEpochSecond(now);
        grant.setExpiresAtEpochSecond(expire);
        grant.setRefreshBeforeExpirySeconds(refreshBefore);
        return grant;
    }

    /**
     * DTO matching the frontend PlaybackSessionGrant contract.
     */
    public static class PlaybackSessionGrant {
        private long trackId;
        private String signedStreamPath;
        private long issuedAtEpochSecond;
        private long expiresAtEpochSecond;
        private int refreshBeforeExpirySeconds;

        public long getTrackId() {
            return trackId;
        }

        public void setTrackId(long trackId) {
            this.trackId = trackId;
        }

        public String getSignedStreamPath() {
            return signedStreamPath;
        }

        public void setSignedStreamPath(String signedStreamPath) {
            this.signedStreamPath = signedStreamPath;
        }

        public long getIssuedAtEpochSecond() {
            return issuedAtEpochSecond;
        }

        public void setIssuedAtEpochSecond(long issuedAtEpochSecond) {
            this.issuedAtEpochSecond = issuedAtEpochSecond;
        }

        public long getExpiresAtEpochSecond() {
            return expiresAtEpochSecond;
        }

        public void setExpiresAtEpochSecond(long expiresAtEpochSecond) {
            this.expiresAtEpochSecond = expiresAtEpochSecond;
        }

        public int getRefreshBeforeExpirySeconds() {
            return refreshBeforeExpirySeconds;
        }

        public void setRefreshBeforeExpirySeconds(int refreshBeforeExpirySeconds) {
            this.refreshBeforeExpirySeconds = refreshBeforeExpirySeconds;
        }
    }
}
