SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND column_name = 'has_lyric'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE track ADD COLUMN has_lyric TINYINT NOT NULL DEFAULT 0 COMMENT ''是否有歌词：1有，0无'' AFTER cover_art_url',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND column_name = 'lyric_path'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE track ADD COLUMN lyric_path VARCHAR(2048) NULL COMMENT ''歌词文件相对路径'' AFTER has_lyric',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
