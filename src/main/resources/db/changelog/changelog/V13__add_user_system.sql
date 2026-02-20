-- V13: Multi-user support
-- 1. Create 'users' table
-- 2. Add 'user_id' to playlist and play_event for data isolation
-- 3. Migrate existing playlists/events to a default user (id=1, admin)

CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'User ID',
    username VARCHAR(64) NOT NULL COMMENT 'Unique login name',
    password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt password hash',
    role VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT 'Role: USER, ADMIN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User account table';

-- Insert default admin user: admin / admin123 (BCrypt hash)
INSERT INTO users (id, username, password_hash, role)
VALUES (1, 'admin', '$2a$10$8.UnVuG9HHgffUDAlk8qfOuVGkqRzgVymGe07xd00DMxs.TVuHOnu', 'ADMIN');

-- Add user_id column to playlist
ALTER TABLE playlist ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1 COMMENT 'Owner User ID';
CREATE INDEX idx_playlist_user_id ON playlist (user_id);

-- Update playlist unique constraints to include user_id
-- We must drop old unique keys and create new ones that are scoped by user_id
-- Note: MySQL requires dropping constraints by name. The names were explicitly set in V6.

-- Drop old constraints
ALTER TABLE playlist DROP INDEX uk_playlist_system_code_deleted;
ALTER TABLE playlist DROP INDEX uk_playlist_name_deleted;

-- Add new scoped constraints
ALTER TABLE playlist ADD UNIQUE KEY uk_playlist_user_system_deleted (user_id, system_code, is_deleted);
ALTER TABLE playlist ADD UNIQUE KEY uk_playlist_user_name_deleted (user_id, name, is_deleted);

-- Add user_id column to play_event
ALTER TABLE play_event ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1 COMMENT 'Owner User ID';
CREATE INDEX idx_play_event_user_id ON play_event (user_id);
