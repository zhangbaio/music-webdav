-- V11: play_event table for tracking playback behavior
-- Used by recommendation engine to compute hot tracks, rediscover, genre mix etc.

CREATE TABLE IF NOT EXISTS play_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_id     BIGINT       NOT NULL,
    event_type   VARCHAR(20)  NOT NULL DEFAULT 'PLAY_START' COMMENT 'PLAY_START | PLAY_COMPLETE | SKIP',
    duration_sec INT          NOT NULL DEFAULT 0 COMMENT 'Actual seconds played before event',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_play_event_track_id (track_id),
    INDEX idx_play_event_created_at (created_at),
    INDEX idx_play_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
