package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.ebook.dto.EbookScrapeResult;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电子书刮削：搜索候选 → 绑定（结果落 DB，不改文件）→ 解绑。
 * 数据源通过 {@link EbookMetadataProvider} 可插拔。封面经 scraperRestTemplate 下载。
 */
@Service
@Slf4j
public class EbookScrapeService {

    private final EbookRepository bookRepository;
    private final Map<String, EbookMetadataProvider> providers;
    private final RestTemplate scraperRestTemplate;

    public EbookScrapeService(EbookRepository bookRepository,
                              List<EbookMetadataProvider> providerList,
                              @Qualifier("scraperRestTemplate") RestTemplate scraperRestTemplate) {
        this.bookRepository = bookRepository;
        this.scraperRestTemplate = scraperRestTemplate;
        this.providers = new LinkedHashMap<>();
        providerList.forEach(p -> providers.put(p.source(), p));
    }

    /** 已注册的数据源清单（前端展示用）。 */
    public List<Map<String, Object>> listProviders() {
        return providers.values().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", p.source());
                    m.put("displayName", p.displayName());
                    return m;
                })
                .toList();
    }

    /** 按关键词搜索候选；source 为空时查询全部数据源。 */
    public List<EbookScrapeResult> search(String keyword, String source) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("搜索关键词不能为空");
        }
        List<EbookScrapeResult> results = new ArrayList<>();
        for (EbookMetadataProvider provider : providers(source)) {
            try {
                results.addAll(provider.search(keyword));
            } catch (Exception e) {
                log.warn("[EbookScrape] Provider '{}' search failed: {}", provider.source(), e.getMessage());
            }
        }
        return results;
    }

    /** 绑定外部元数据：结果落 DB（不改文件），封面下载到书目录。 */
    public Ebook bind(Long bookId, String source, String sourceId) {
        Ebook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", bookId));
        EbookMetadataProvider provider = providers.get(source);
        if (provider == null) {
            throw new BadRequestException("未知数据源: " + source);
        }
        EbookScrapeResult detail;
        try {
            detail = provider.fetch(sourceId);
        } catch (Exception e) {
            throw new BadRequestException("获取元数据失败: " + e.getMessage());
        }
        if (detail == null) {
            throw new ResourceNotFoundException("ScrapeResult", "sourceId", sourceId);
        }

        applyScraped(book, detail);
        log.info("[EbookScrape] Bound book {} <- {}/{}", bookId, source, sourceId);
        return bookRepository.save(book);
    }

    /** 解绑：清除刮削痕迹，已写入的字段保留。 */
    public Ebook unbind(Long bookId) {
        Ebook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", bookId));
        book.setSourceId(null);
        book.setMetadataSource("manual");
        return bookRepository.save(book);
    }

    private void applyScraped(Ebook book, EbookScrapeResult detail) {
        book.setTitle(firstNonBlank(detail.getTitle(), book.getTitle()));
        book.setAuthor(firstNonBlank(detail.getAuthor(), book.getAuthor()));
        book.setPublisher(firstNonBlank(detail.getPublisher(), book.getPublisher()));
        book.setOverview(firstNonBlank(detail.getOverview(), book.getOverview()));
        book.setSeries(firstNonBlank(detail.getSeries(), book.getSeries()));
        if (detail.getSeriesPart() != null) book.setSeriesPart(detail.getSeriesPart());
        if (detail.getPubYear() != null) book.setPubYear(detail.getPubYear());
        book.setSourceId(firstNonBlank(detail.getSourceId(), book.getSourceId()));
        book.setMetadataSource("scrape");

        if (detail.getCoverUrl() != null && !detail.getCoverUrl().isBlank()) {
            downloadCover(book, detail.getCoverUrl());
        }
    }

    private void downloadCover(Ebook book, String coverUrl) {
        Path bookDir = Paths.get(book.getFilePath()).getParent();
        if (bookDir == null || !Files.isDirectory(bookDir)) return;
        Path target = bookDir.resolve("cover.jpg");
        try {
            byte[] body = scraperRestTemplate.getForObject(coverUrl, byte[].class);
            if (body != null && body.length > 0) {
                Files.write(target, body);
                book.setCoverArtPath(target.toString());
                log.debug("[EbookScrape] Cover saved: {}", target);
            } else {
                log.warn("[EbookScrape] Cover download failed: empty body");
            }
        } catch (Exception e) {
            log.warn("[EbookScrape] Cover download error: {}", e.getMessage());
        }
    }

    private List<EbookMetadataProvider> providers(String source) {
        if (source != null && !source.isBlank()) {
            EbookMetadataProvider p = providers.get(source);
            if (p == null) throw new BadRequestException("未知数据源: " + source);
            return List.of(p);
        }
        return List.copyOf(providers.values());
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }
}
