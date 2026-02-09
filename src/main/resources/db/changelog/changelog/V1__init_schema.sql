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
  error_summary VARCHAR(1000) NULL COMMENT '错误摘要信息',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_scan_task_status (status),
  KEY idx_scan_task_start_time (start_time),
  KEY idx_scan_task_config_id (config_id)
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
  year INT NULL COMMENT '发行年份',
  genre VARCHAR(255) NULL COMMENT '流派',
  duration_sec INT NULL COMMENT '时长（秒）',
  bitrate INT NULL COMMENT '码率（kbps）',
  sample_rate INT NULL COMMENT '采样率（Hz）',
  channels INT NULL COMMENT '声道数',
  has_cover TINYINT NOT NULL DEFAULT 0 COMMENT '是否有封面：1有，0无',
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
  KEY idx_track_is_deleted (is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌曲信息表';
