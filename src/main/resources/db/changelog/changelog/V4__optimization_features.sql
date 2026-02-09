-- Feature 3: Cover art URL on track table
ALTER TABLE track ADD COLUMN cover_art_url VARCHAR(2048) NULL COMMENT '目录级封面图URL' AFTER has_cover;

-- Feature 4: Directory signature table for incremental checking
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

-- Feature 6: Scan checkpoint table
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

-- Feature 7: Progress columns on scan_task
ALTER TABLE scan_task ADD COLUMN processed_directories INT NULL DEFAULT 0 COMMENT '已处理目录数' AFTER failed_count;
ALTER TABLE scan_task ADD COLUMN total_directories INT NULL DEFAULT 0 COMMENT '总目录数' AFTER processed_directories;
ALTER TABLE scan_task ADD COLUMN last_synced_dir VARCHAR(2048) NULL COMMENT '最后同步目录' AFTER total_directories;
ALTER TABLE scan_task ADD COLUMN progress_pct INT NULL DEFAULT 0 COMMENT '进度百分比' AFTER last_synced_dir;
