-- ========================================
-- 电子书书源功能数据库迁移脚本
-- 注意：此脚本仅供参考，实际 schema 更新由 Hibernate 自动完成
-- ========================================

-- 1. 扩展 ebooks 表：添加在线书源相关字段
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS source_type VARCHAR(10) DEFAULT 'LOCAL' NOT NULL;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS book_source_id BIGINT;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS online_url TEXT;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS chapters_json TEXT;

-- 2. 创建 book_sources 表
CREATE TABLE IF NOT EXISTS book_sources (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    name VARCHAR(255) NOT NULL,
    author VARCHAR(255),
    version VARCHAR(50),
    url VARCHAR(500) NOT NULL,
    rule_json TEXT NOT NULL,
    clean_rule_json TEXT,
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    "group" VARCHAR(100),
    source_type VARCHAR(50) DEFAULT 'novel' NOT NULL,
    header_json TEXT,
    sort_order INTEGER DEFAULT 0 NOT NULL,
    description TEXT,
    deleted BOOLEAN DEFAULT FALSE NOT NULL
);

-- 3. 创建索引
CREATE INDEX IF NOT EXISTS idx_book_source_enabled ON book_sources(enabled);
CREATE INDEX IF NOT EXISTS idx_book_source_name ON book_sources(name);
CREATE INDEX IF NOT EXISTS idx_book_source_group ON book_sources("group");
CREATE INDEX IF NOT EXISTS idx_book_source_deleted ON book_sources(deleted);
CREATE INDEX IF NOT EXISTS idx_ebook_source_type ON ebooks(source_type);
CREATE INDEX IF NOT EXISTS idx_ebook_book_source_id ON ebooks(book_source_id);

-- 4. 添加外键约束（如果不存在）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_ebook_book_source'
    ) THEN
        ALTER TABLE ebooks ADD CONSTRAINT fk_ebook_book_source 
            FOREIGN KEY (book_source_id) REFERENCES book_sources(id);
    END IF;
END $$;

-- 5. 插入默认书源（可选）
-- INSERT INTO book_sources (name, url, rule_json, source_type, enabled) 
-- VALUES ('默认书源', 'https://example.com', '{}', 'novel', false);
