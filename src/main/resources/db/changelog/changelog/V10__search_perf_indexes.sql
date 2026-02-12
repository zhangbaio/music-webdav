-- Search-path indexes for artist/album/title lookups and ordering.
-- These indexes target high-frequency query patterns in SearchMapper.

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND index_name = 'idx_track_deleted_artist_updated'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE track ADD KEY idx_track_deleted_artist_updated (is_deleted, artist(128), updated_at, id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND index_name = 'idx_track_deleted_album_artist_updated'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE track ADD KEY idx_track_deleted_album_artist_updated (is_deleted, album(128), artist(128), updated_at, id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND index_name = 'idx_track_deleted_title_updated'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE track ADD KEY idx_track_deleted_title_updated (is_deleted, title(128), updated_at, id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
