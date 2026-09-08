package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.ebook.dto.EbookScrapeResult;

import java.util.List;

/** 电子书元数据源接口（可插拔，与 audiobook 的 AudiobookMetadataProvider 同构）。 */
public interface EbookMetadataProvider {

    /** 数据源标识（小写），如 bangumi */
    String source();

    /** 前端展示名 */
    String displayName();

    List<EbookScrapeResult> search(String keyword) throws Exception;

    EbookScrapeResult fetch(String sourceId) throws Exception;
}
