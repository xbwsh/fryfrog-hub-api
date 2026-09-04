package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;

import java.util.List;

/**
 * 有声书元数据源接口。实现类负责将外部数据源映射为统一的
 * {@link AudiobookScrapeResult}，实现类需声明唯一的 source 标识。
 */
public interface AudiobookMetadataProvider {

    /** 数据源标识（小写），如 itunes */
    String source();

    /** 显示名 */
    String displayName();

    /** 按关键词搜索候选列表 */
    List<AudiobookScrapeResult> search(String keyword) throws Exception;

    /** 按数据源 ID 取详情 */
    AudiobookScrapeResult fetch(String sourceId) throws Exception;
}
