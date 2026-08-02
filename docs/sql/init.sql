-- Fryfrog Hub Database Schema
-- Database: PostgreSQL

-- =====================================================
-- 媒体资源库表
-- =====================================================
CREATE TABLE IF NOT EXISTS media_libraries (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    type VARCHAR(20) NOT NULL,
    sub_type VARCHAR(20),
    enabled BOOLEAN DEFAULT TRUE,
    enable_scraping BOOLEAN DEFAULT TRUE,
    is_adult BOOLEAN DEFAULT FALSE,
    sort_order INTEGER DEFAULT 0,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- 视频系列表
-- =====================================================
CREATE TABLE IF NOT EXISTS video_series (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    original_title VARCHAR(255),
    overview TEXT,
    media_type VARCHAR(20),
    tmdb_id BIGINT,
    imdb_id VARCHAR(50),
    rating DOUBLE PRECISION,
    year INTEGER,
    poster_url TEXT,
    backdrop_url TEXT,
    poster_local_path VARCHAR(1024),
    backdrop_local_path VARCHAR(1024),
    metadata_source VARCHAR(50),
    status VARCHAR(50),
    is_adult BOOLEAN DEFAULT FALSE,
    number_of_seasons INTEGER,
    season_number INTEGER DEFAULT 1,
    total_episodes INTEGER,
    metadata_dir VARCHAR(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_series_tmdb_id ON video_series(tmdb_id);
CREATE INDEX IF NOT EXISTS idx_series_title ON video_series(title);

-- =====================================================
-- 视频表
-- =====================================================
CREATE TABLE IF NOT EXISTS videos (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    original_title VARCHAR(255),
    overview TEXT,
    media_type VARCHAR(20),
    year INTEGER,
    rating DOUBLE PRECISION,
    file_path VARCHAR(1024) UNIQUE NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    file_size BIGINT,
    format VARCHAR(50),
    duration_seconds BIGINT,
    width INTEGER,
    height INTEGER,
    video_codec VARCHAR(50),
    audio_codec VARCHAR(50),
    bitrate_kbps INTEGER,
    cover_art_path VARCHAR(1024),
    backdrop_local_path VARCHAR(1024),
    poster_url TEXT,
    backdrop_url TEXT,
    favorite BOOLEAN DEFAULT FALSE,
    is_adult BOOLEAN DEFAULT FALSE,
    is_series BOOLEAN DEFAULT FALSE,
    series_name VARCHAR(255),
    season_number INTEGER,
    episode_number INTEGER,
    library_id BIGINT,
    series_id BIGINT,
    tmdb_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (series_id) REFERENCES video_series(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_video_series_id ON videos(series_id);
CREATE INDEX IF NOT EXISTS idx_video_library_id ON videos(library_id);
CREATE INDEX IF NOT EXISTS idx_video_title ON videos(title);
CREATE INDEX IF NOT EXISTS idx_video_favorite ON videos(favorite);

-- =====================================================
-- 视频演员表
-- =====================================================
CREATE TABLE IF NOT EXISTS video_actors (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(255),
    thumb_url TEXT,
    profile_path VARCHAR(1024),
    video_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_actor_video_id ON video_actors(video_id);

-- =====================================================
-- 观看进度表
-- =====================================================
CREATE TABLE IF NOT EXISTS watch_progress (
    id BIGSERIAL PRIMARY KEY,
    video_id BIGINT UNIQUE NOT NULL,
    position_seconds DOUBLE PRECISION DEFAULT 0,
    duration_seconds DOUBLE PRECISION,
    completed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
);

-- =====================================================
-- 系统设置表
-- =====================================================
CREATE TABLE IF NOT EXISTS system_setting (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(255) UNIQUE NOT NULL,
    setting_value TEXT,
    description VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
