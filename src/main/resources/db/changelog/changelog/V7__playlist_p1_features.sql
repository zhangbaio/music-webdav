ALTER TABLE playlist
  ADD COLUMN sort_no INT NOT NULL DEFAULT 0 COMMENT '歌单排序值（越小越靠前）' AFTER system_code;

UPDATE playlist
SET sort_no = CASE
  WHEN playlist_type = 'SYSTEM' THEN 0
  ELSE id * 10
END
WHERE sort_no = 0;

ALTER TABLE playlist
  ADD KEY idx_playlist_deleted_sort (is_deleted, sort_no, id);
