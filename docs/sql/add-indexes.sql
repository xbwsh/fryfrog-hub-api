-- Fryfrog Hub Database Migration
-- 添加索引优化查询性能
-- Database: SQLite

-- videos 表索引
CREATE INDEX IF NOT EXISTS idx_video_imdb_id ON videos(imdb_id);
CREATE INDEX IF NOT EXISTS idx_video_is_adult ON videos(is_adult);

-- video_series 表索引
CREATE INDEX IF NOT EXISTS idx_series_imdb_id ON video_series(imdb_id);
