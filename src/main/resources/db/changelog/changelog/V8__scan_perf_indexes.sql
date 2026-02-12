SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'scan_task'
    AND index_name = 'idx_scan_task_config_status'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE scan_task ADD KEY idx_scan_task_config_status (config_id, status)',
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
    AND index_name = 'idx_track_config_deleted_last_scan'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE track ADD KEY idx_track_config_deleted_last_scan (source_config_id, is_deleted, last_scan_task_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
