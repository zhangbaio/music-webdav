SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND column_name = 'source_config_id'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE track ADD COLUMN source_config_id BIGINT NULL COMMENT ''来源WebDAV配置ID'' AFTER id',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE track t
LEFT JOIN scan_task st ON st.id = t.last_scan_task_id
SET t.source_config_id = st.config_id
WHERE t.source_config_id IS NULL
  AND st.config_id IS NOT NULL;

UPDATE track
SET source_config_id = 0
WHERE source_config_id IS NULL;

ALTER TABLE track
  MODIFY COLUMN source_config_id BIGINT NOT NULL COMMENT '来源WebDAV配置ID';

ALTER TABLE track
  MODIFY COLUMN source_path_md5 CHAR(32) NOT NULL COMMENT '路径MD5（配置内唯一）';

SET @old_uk_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND index_name = 'uk_track_path_md5'
);
SET @sql = IF(
  @old_uk_exists > 0,
  'ALTER TABLE track DROP INDEX uk_track_path_md5',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @new_uk_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND index_name = 'uk_track_config_path_md5'
);
SET @sql = IF(
  @new_uk_exists = 0,
  'ALTER TABLE track ADD UNIQUE KEY uk_track_config_path_md5 (source_config_id, source_path_md5)',
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
    AND index_name = 'idx_track_source_config_deleted'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE track ADD KEY idx_track_source_config_deleted (source_config_id, is_deleted)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
