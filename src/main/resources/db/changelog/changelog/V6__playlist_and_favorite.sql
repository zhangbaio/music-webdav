CREATE TABLE IF NOT EXISTS playlist (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '歌单ID',
  name VARCHAR(128) NOT NULL COMMENT '歌单名称',
  playlist_type VARCHAR(16) NOT NULL COMMENT '歌单类型：NORMAL普通，SYSTEM系统',
  system_code VARCHAR(32) NULL COMMENT '系统歌单编码：FAVORITES',
  is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否软删除：1已删除，0正常',
  track_count INT NOT NULL DEFAULT 0 COMMENT '歌曲数量',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_playlist_system_code_deleted (system_code, is_deleted),
  UNIQUE KEY uk_playlist_name_deleted (name, is_deleted),
  KEY idx_playlist_type_deleted (playlist_type, is_deleted),
  KEY idx_playlist_updated_at (updated_at)
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

INSERT INTO playlist(name, playlist_type, system_code, is_deleted, track_count)
SELECT 'Favorites', 'SYSTEM', 'FAVORITES', 0, 0
WHERE NOT EXISTS (
  SELECT 1 FROM playlist WHERE system_code = 'FAVORITES' AND is_deleted = 0
);
