-- ============================================================
-- WebDAV Music DB All-In-One Repeatable SQL (MySQL 5.7)
-- Merge source: V1 ~ V8
-- Repeatable execution: YES
-- ============================================================

-- ----------------------------
-- 1) Base tables (latest shape)
-- ----------------------------
CREATE TABLE IF NOT EXISTS webdav_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  name VARCHAR(64) NOT NULL COMMENT '配置名称',
  base_url VARCHAR(512) NOT NULL COMMENT 'WebDAV服务地址',
  username VARCHAR(128) NOT NULL COMMENT 'WebDAV用户名',
  password_enc VARCHAR(512) NOT NULL COMMENT '加密后的密码或令牌',
  root_path VARCHAR(1024) NOT NULL COMMENT '扫描根目录',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0禁用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_webdav_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WebDAV连接配置表';

CREATE TABLE IF NOT EXISTS scan_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
  task_type VARCHAR(16) NOT NULL COMMENT '任务类型：FULL全量，INCREMENTAL增量',
  status VARCHAR(24) NOT NULL COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/PARTIAL_SUCCESS/CANCELED',
  config_id BIGINT NOT NULL COMMENT '关联WebDAV配置ID',
  start_time DATETIME NULL COMMENT '任务开始时间',
  end_time DATETIME NULL COMMENT '任务结束时间',
  total_files INT NOT NULL DEFAULT 0 COMMENT '扫描到的总文件数',
  audio_files INT NOT NULL DEFAULT 0 COMMENT '识别出的音频文件数',
  added_count INT NOT NULL DEFAULT 0 COMMENT '新增歌曲数',
  updated_count INT NOT NULL DEFAULT 0 COMMENT '更新歌曲数',
  deleted_count INT NOT NULL DEFAULT 0 COMMENT '软删除歌曲数',
  failed_count INT NOT NULL DEFAULT 0 COMMENT '失败文件数',
  processed_directories INT NULL DEFAULT 0 COMMENT '已处理目录数',
  total_directories INT NULL DEFAULT 0 COMMENT '总目录数',
  last_synced_dir VARCHAR(2048) NULL COMMENT '最后同步目录',
  progress_pct INT NULL DEFAULT 0 COMMENT '进度百分比',
  error_summary VARCHAR(1000) NULL COMMENT '错误摘要信息',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_scan_task_status (status),
  KEY idx_scan_task_start_time (start_time),
  KEY idx_scan_task_config_id (config_id),
  KEY idx_scan_task_config_status (config_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描任务表';

CREATE TABLE IF NOT EXISTS scan_task_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '明细ID',
  task_id BIGINT NOT NULL COMMENT '扫描任务ID',
  source_path VARCHAR(2048) NOT NULL COMMENT 'WebDAV文件相对路径',
  source_path_md5 CHAR(32) NOT NULL COMMENT '路径MD5（用于索引加速）',
  item_status VARCHAR(24) NOT NULL COMMENT '明细状态：SUCCESS/FAILED/SKIPPED',
  error_code VARCHAR(64) NULL COMMENT '错误码',
  error_message VARCHAR(1000) NULL COMMENT '错误信息',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_scan_item_task_id (task_id),
  KEY idx_scan_item_status (item_status),
  KEY idx_scan_item_path_md5 (source_path_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描任务文件明细表';

CREATE TABLE IF NOT EXISTS scan_task_seen_file (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  task_id BIGINT NOT NULL COMMENT '扫描任务ID',
  source_path_md5 CHAR(32) NOT NULL COMMENT '任务中发现的路径MD5',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_task_seen_path (task_id, source_path_md5),
  KEY idx_seen_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描任务已发现文件集合表';

CREATE TABLE IF NOT EXISTS track (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '歌曲ID',
  source_config_id BIGINT NOT NULL COMMENT '来源WebDAV配置ID',
  source_path VARCHAR(2048) NOT NULL COMMENT 'WebDAV文件相对路径',
  source_path_md5 CHAR(32) NOT NULL COMMENT '路径MD5（配置内唯一）',
  source_etag VARCHAR(255) NULL COMMENT 'WebDAV ETag',
  source_last_modified DATETIME NULL COMMENT '源文件最后修改时间',
  source_size BIGINT NULL COMMENT '源文件大小（字节）',
  mime_type VARCHAR(128) NULL COMMENT '文件MIME类型',
  content_hash CHAR(64) NULL COMMENT '文件内容哈希（可选）',
  title VARCHAR(512) NOT NULL COMMENT '歌曲标题',
  artist VARCHAR(512) NOT NULL COMMENT '歌手',
  album VARCHAR(512) NOT NULL COMMENT '专辑',
  album_artist VARCHAR(512) NULL COMMENT '专辑歌手',
  track_no INT NULL COMMENT '曲目号',
  disc_no INT NULL COMMENT '碟片序号',
  `year` INT NULL COMMENT '发行年份',
  genre VARCHAR(255) NULL COMMENT '流派',
  duration_sec INT NULL COMMENT '时长（秒）',
  bitrate INT NULL COMMENT '码率（kbps）',
  sample_rate INT NULL COMMENT '采样率（Hz）',
  channels INT NULL COMMENT '声道数',
  has_cover TINYINT NOT NULL DEFAULT 0 COMMENT '是否有封面：1有，0无',
  cover_art_url VARCHAR(2048) NULL COMMENT '目录级封面图URL',
  has_lyric TINYINT NOT NULL DEFAULT 0 COMMENT '是否有歌词：1有，0无',
  lyric_path VARCHAR(2048) NULL COMMENT '歌词文件相对路径',
  is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否软删除：1已删除，0正常',
  last_scan_task_id BIGINT NULL COMMENT '最后一次更新该记录的任务ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_track_config_path_md5 (source_config_id, source_path_md5),
  KEY idx_track_source_config_deleted (source_config_id, is_deleted),
  KEY idx_track_title_artist_album (title(191), artist(191), album(191)),
  KEY idx_track_artist (artist(191)),
  KEY idx_track_album (album(191)),
  KEY idx_track_updated_at (updated_at),
  KEY idx_track_is_deleted (is_deleted),
  KEY idx_track_config_deleted_last_scan (source_config_id, is_deleted, last_scan_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌曲信息表';

CREATE TABLE IF NOT EXISTS directory_signature (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  config_id BIGINT NOT NULL COMMENT 'WebDAV配置ID',
  dir_path VARCHAR(2048) NOT NULL COMMENT '目录相对路径',
  dir_path_md5 CHAR(32) NOT NULL COMMENT '目录路径MD5',
  dir_etag VARCHAR(255) NULL COMMENT '目录ETag',
  dir_last_modified DATETIME NULL COMMENT '目录最后修改时间',
  child_count INT NULL COMMENT '直接子项数量',
  last_verified_at DATETIME NOT NULL COMMENT '最后验证时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_dir_sig_config_path (config_id, dir_path_md5),
  KEY idx_dir_sig_config (config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目录签名表（增量扫描优化）';

CREATE TABLE IF NOT EXISTS scan_checkpoint (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  task_id BIGINT NOT NULL COMMENT '扫描任务ID',
  dir_path VARCHAR(2048) NOT NULL COMMENT '目录相对路径',
  dir_path_md5 CHAR(32) NOT NULL COMMENT '目录路径MD5',
  status VARCHAR(16) NOT NULL COMMENT 'COMPLETED/FAILED',
  file_count INT NULL COMMENT '目录中文件数',
  processed_count INT NULL COMMENT '已处理文件数',
  failed_count INT NULL COMMENT '失败文件数',
  error_message VARCHAR(1000) NULL COMMENT '失败原因',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_checkpoint_task (task_id),
  UNIQUE KEY uk_checkpoint_task_dir (task_id, dir_path_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描检查点表';

CREATE TABLE IF NOT EXISTS playlist (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '歌单ID',
  name VARCHAR(128) NOT NULL COMMENT '歌单名称',
  playlist_type VARCHAR(16) NOT NULL COMMENT '歌单类型：NORMAL普通，SYSTEM系统',
  system_code VARCHAR(32) NULL COMMENT '系统歌单编码：FAVORITES',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '歌单排序值（越小越靠前）',
  is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否软删除：1已删除，0正常',
  track_count INT NOT NULL DEFAULT 0 COMMENT '歌曲数量',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_playlist_system_code_deleted (system_code, is_deleted),
  UNIQUE KEY uk_playlist_name_deleted (name, is_deleted),
  KEY idx_playlist_type_deleted (playlist_type, is_deleted),
  KEY idx_playlist_updated_at (updated_at),
  KEY idx_playlist_deleted_sort (is_deleted, sort_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌单表';

CREATE TABLE IF NOT EXISTS playlist_track (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  playlist_id BIGINT NOT NULL COMMENT '歌单ID',
  track_id BIGINT NOT NULL COMMENT '歌曲ID',
  order_no INT NOT NULL DEFAULT 0 COMMENT '歌单内顺序',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_playlist_track (playlist_id, track_id),
  KEY idx_playlist_order (playlist_id, order_no, id),
  KEY idx_playlist_track (track_id, playlist_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌单歌曲关系表';

-- ----------------------------
-- 2) Backward-compatible idempotent migrations
-- ----------------------------

-- 2.1 track.source_config_id (legacy compatibility)
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

-- 2.2 cover_art_url / has_lyric / lyric_path
SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND column_name = 'cover_art_url'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE track ADD COLUMN cover_art_url VARCHAR(2048) NULL COMMENT ''目录级封面图URL'' AFTER has_cover',
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

-- 2.3 scan_task progress columns
SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'scan_task'
    AND column_name = 'processed_directories'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE scan_task ADD COLUMN processed_directories INT NULL DEFAULT 0 COMMENT ''已处理目录数'' AFTER failed_count',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'scan_task'
    AND column_name = 'total_directories'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE scan_task ADD COLUMN total_directories INT NULL DEFAULT 0 COMMENT ''总目录数'' AFTER processed_directories',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'scan_task'
    AND column_name = 'last_synced_dir'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE scan_task ADD COLUMN last_synced_dir VARCHAR(2048) NULL COMMENT ''最后同步目录'' AFTER total_directories',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'scan_task'
    AND column_name = 'progress_pct'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE scan_task ADD COLUMN progress_pct INT NULL DEFAULT 0 COMMENT ''进度百分比'' AFTER last_synced_dir',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2.4 playlist.sort_no
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

-- ----------------------------
-- 3) Index idempotent fixes
-- ----------------------------

-- 3.1 legacy unique index migration on track
SET @idx_exists = (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'track'
    AND index_name = 'uk_track_path_md5'
);
SET @sql = IF(
  @idx_exists > 0,
  'ALTER TABLE track DROP INDEX uk_track_path_md5',
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
    AND index_name = 'uk_track_config_path_md5'
);
SET @sql = IF(
  @idx_exists = 0,
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

-- ----------------------------
-- 4) System data init (idempotent)
-- ----------------------------
INSERT INTO playlist(name, playlist_type, system_code, is_deleted, track_count)
SELECT 'Favorites', 'SYSTEM', 'FAVORITES', 0, 0
WHERE NOT EXISTS (
  SELECT 1
  FROM playlist
  WHERE system_code = 'FAVORITES'
    AND is_deleted = 0
);

