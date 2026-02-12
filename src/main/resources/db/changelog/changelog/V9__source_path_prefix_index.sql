-- Add prefix index on track.source_path to accelerate LIKE prefix queries
-- used by touchLastScanTaskByDirectoryPrefix: source_path LIKE 'dir/%'
-- Composite (source_config_id, is_deleted, source_path(255)) covers the WHERE clause fully.

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND index_name = 'idx_track_config_deleted_path_prefix'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE track ADD KEY idx_track_config_deleted_path_prefix (source_config_id, is_deleted, source_path(255))',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
