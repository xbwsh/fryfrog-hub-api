-- Fryfrog Hub Database Migration
-- Database: SQLite

-- video_series 表新增 is_adult 字段
ALTER TABLE video_series ADD COLUMN is_adult boolean DEFAULT false;

-- 将已有视频的 is_adult 同步到所属系列
UPDATE video_series SET is_adult = 1 WHERE id IN (
    SELECT DISTINCT v.series_id FROM videos v WHERE v.is_adult = 1 AND v.series_id IS NOT NULL
);

-- videos 表索引
CREATE INDEX IF NOT EXISTS idx_video_imdb_id ON videos(imdb_id);
CREATE INDEX IF NOT EXISTS idx_video_is_adult ON videos(is_adult);

-- video_series 表索引
CREATE INDEX IF NOT EXISTS idx_series_imdb_id ON video_series(imdb_id);
