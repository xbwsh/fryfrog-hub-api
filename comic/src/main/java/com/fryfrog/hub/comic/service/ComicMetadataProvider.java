package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.dto.ComicScrapeResult;

import java.util.List;

/** 漫画元数据源接口（可插拔，与 ebook 的 EbookMetadataProvider 同构）。 */
public interface ComicMetadataProvider {

    /** 数据源标识（小写），如 bangumi */
    String source();

    /** 前端展示名 */
    String displayName();

    List<ComicScrapeResult> search(String keyword) throws Exception;

    ComicScrapeResult fetch(String sourceId) throws Exception;
}
