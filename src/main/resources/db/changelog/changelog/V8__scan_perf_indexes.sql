ALTER TABLE scan_task
  ADD KEY idx_scan_task_config_status (config_id, status);

ALTER TABLE track
  ADD KEY idx_track_config_deleted_last_scan (source_config_id, is_deleted, last_scan_task_id);
