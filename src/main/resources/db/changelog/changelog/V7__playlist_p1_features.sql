SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'playlist'
    AND column_name = 'sort_no'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE playlist ADD COLUMN sort_no INT NOT NULL DEFAULT 0 COMMENT ''歌单排序值（越小越靠前）'' AFTER system_code',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE playlist
SET sort_no = CASE
  WHEN playlist_type = 'SYSTEM' THEN 0
  ELSE id * 10
END
WHERE sort_no = 0;

SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'playlist'
    AND index_name = 'idx_playlist_deleted_sort'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE playlist ADD KEY idx_playlist_deleted_sort (is_deleted, sort_no, id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
