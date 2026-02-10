ALTER TABLE track
    ADD COLUMN has_lyric TINYINT NOT NULL DEFAULT 0 COMMENT '是否有歌词：1有，0无' AFTER cover_art_url,
    ADD COLUMN lyric_path VARCHAR(2048) NULL COMMENT '歌词文件相对路径' AFTER has_lyric;
