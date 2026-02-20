-- V12: Support for Smart Playlists with dynamic rules
-- Adds 'rules' column to playlist table.

ALTER TABLE playlist ADD COLUMN rules TEXT NULL COMMENT 'Smart playlist rules (JSON)';

-- Add some default smart playlists if they don't exist
INSERT INTO playlist(name, playlist_type, system_code, is_deleted, track_count, rules)
SELECT '最近添加', 'SYSTEM', 'RECENTLY_ADDED_SMART', 0, 0, '{"limit": 50, "sortBy": "created_at", "sortOrder": "DESC"}'
WHERE NOT EXISTS (
  SELECT 1 FROM playlist WHERE system_code = 'RECENTLY_ADDED_SMART' AND is_deleted = 0
);

INSERT INTO playlist(name, playlist_type, system_code, is_deleted, track_count, rules)
SELECT '最近播放', 'SYSTEM', 'RECENTLY_PLAYED_SMART', 0, 0, '{"limit": 50, "sortBy": "last_played_at", "sortOrder": "DESC"}'
WHERE NOT EXISTS (
  SELECT 1 FROM playlist WHERE system_code = 'RECENTLY_PLAYED_SMART' AND is_deleted = 0
);
