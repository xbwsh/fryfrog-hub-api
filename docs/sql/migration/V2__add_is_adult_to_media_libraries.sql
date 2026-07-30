-- 添加 is_adult 字段到 media_libraries 表
-- 用于支持资源库级成人内容标记

-- PostgreSQL
ALTER TABLE media_libraries ADD COLUMN IF NOT EXISTS is_adult BOOLEAN DEFAULT FALSE;

-- 更新现有行，确保 is_adult 不为 NULL
UPDATE media_libraries SET is_adult = FALSE WHERE is_adult IS NULL;
