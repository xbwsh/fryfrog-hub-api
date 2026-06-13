-- Fryfrog Hub Database Schema
-- Database: MySQL 8.0+

CREATE DATABASE IF NOT EXISTS fryfrog_hub
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE fryfrog_hub;

-- =====================================================
-- 音乐曲目表
-- =====================================================
CREATE TABLE IF NOT EXISTS music_tracks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- 基本信息
    title VARCHAR(255) NOT NULL COMMENT '歌曲标题',
    artist VARCHAR(255) COMMENT '艺术家',
    album VARCHAR(255) COMMENT '专辑名称',
    album_artist VARCHAR(255) COMMENT '专辑艺术家',

    -- 曲目信息
    track_number INT COMMENT '曲目编号',
    disc_number INT COMMENT '碟片编号',
    `year` INT COMMENT '发行年份',
    genre VARCHAR(100) COMMENT '音乐流派',

    -- 文件信息
    file_path VARCHAR(1024) NOT NULL COMMENT '文件完整路径',
    file_name VARCHAR(512) NOT NULL COMMENT '文件名',
    file_size BIGINT COMMENT '文件大小（字节）',

    -- 音频信息
    duration_seconds BIGINT COMMENT '时长（秒）',
    bitrate_kbps INT COMMENT '比特率（kbps）',
    format VARCHAR(50) COMMENT '音频格式，如 FLAC 16 bits',

    -- 媒体信息
    cover_art_path VARCHAR(1024) COMMENT '封面图片缓存路径',
    lyrics TEXT COMMENT '歌词文本',

    -- 用户数据
    favorite TINYINT(1) DEFAULT 0 COMMENT '是否收藏（0=否，1=是）',

    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    UNIQUE INDEX uk_file_path (file_path(255)),
    INDEX idx_title (title),
    INDEX idx_artist (artist),
    INDEX idx_album (album),
    INDEX idx_favorite (favorite),
    INDEX idx_created_at (created_at)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='音乐曲目表';

-- =====================================================
-- 未来扩展：播放历史表（可选）
-- =====================================================
-- CREATE TABLE IF NOT EXISTS play_history (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     track_id BIGINT NOT NULL COMMENT '曲目ID',
--     played_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '播放时间',
--     play_duration INT COMMENT '播放时长（秒）',
--     play_count INT DEFAULT 1 COMMENT '播放次数',
--
--     INDEX idx_track_id (track_id),
--     INDEX idx_played_at (played_at),
--     FOREIGN KEY (track_id) REFERENCES music_tracks(id) ON DELETE CASCADE
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='播放历史表';

-- =====================================================
-- 未来扩展：播放列表表（可选）
-- =====================================================
-- CREATE TABLE IF NOT EXISTS playlists (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     name VARCHAR(255) NOT NULL COMMENT '播放列表名称',
--     description TEXT COMMENT '播放列表描述',
--     cover_image VARCHAR(1024) COMMENT '封面图片路径',
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='播放列表表';
--
-- CREATE TABLE IF NOT EXISTS playlist_tracks (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     playlist_id BIGINT NOT NULL,
--     track_id BIGINT NOT NULL,
--     sort_order INT DEFAULT 0 COMMENT '排序顺序',
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--
--     INDEX idx_playlist_id (playlist_id),
--     INDEX idx_track_id (track_id),
--     FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
--     FOREIGN KEY (track_id) REFERENCES music_tracks(id) ON DELETE CASCADE
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='播放列表关联表';
