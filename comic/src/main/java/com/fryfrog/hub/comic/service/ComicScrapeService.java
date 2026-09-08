package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.comic.dto.ComicScrapeResult;
import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.repository.ComicRepository;
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
 * 漫画刮削：搜索候选 → 绑定（结果落 DB，不改文件）→ 解绑。
 * 数据源通过 {@link ComicMetadataProvider} 可插拔。封面经 scraperRestTemplate 下载。
 */
@Service
@Slf4j
public class ComicScrapeService {

    private final ComicRepository comicRepository;
    private final Map<String, ComicMetadataProvider> providers;
    private final RestTemplate scraperRestTemplate;

    public ComicScrapeService(ComicRepository comicRepository,
                              List<ComicMetadataProvider> providerList,
                              @Qualifier("scraperRestTemplate") RestTemplate scraperRestTemplate) {
        this.comicRepository = comicRepository;
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
    public List<ComicScrapeResult> search(String keyword, String source) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("搜索关键词不能为空");
        }
        List<ComicScrapeResult> results = new ArrayList<>();
        for (ComicMetadataProvider provider : providers(source)) {
            try {
                results.addAll(provider.search(keyword));
            } catch (Exception e) {
                log.warn("[ComicScrape] Provider '{}' search failed: {}", provider.source(), e.getMessage());
            }
        }
        return results;
    }

    /** 绑定外部元数据：结果落 DB（不改文件），封面下载到作品目录。 */
    public Comic bind(Long comicId, String source, String sourceId) {
        Comic comic = comicRepository.findById(comicId)
                .orElseThrow(() -> new ResourceNotFoundException("Comic", "id", comicId));
        ComicMetadataProvider provider = providers.get(source);
        if (provider == null) {
            throw new BadRequestException("未知数据源: " + source);
        }
        ComicScrapeResult detail;
        try {
            detail = provider.fetch(sourceId);
        } catch (Exception e) {
            throw new BadRequestException("获取元数据失败: " + e.getMessage());
        }
        if (detail == null) {
            throw new ResourceNotFoundException("ScrapeResult", "sourceId", sourceId);
        }

        applyScraped(comic, detail);
        log.info("[ComicScrape] Bound comic {} <- {}/{}", comicId, source, sourceId);
        return comicRepository.save(comic);
    }

    /** 解绑：清除刮削痕迹，已写入的字段保留。 */
    public Comic unbind(Long comicId) {
        Comic comic = comicRepository.findById(comicId)
                .orElseThrow(() -> new ResourceNotFoundException("Comic", "id", comicId));
        comic.setSourceId(null);
        comic.setMetadataSource("manual");
        return comicRepository.save(comic);
    }

    private void applyScraped(Comic comic, ComicScrapeResult detail) {
        comic.setTitle(firstNonBlank(detail.getTitle(), comic.getTitle()));
        comic.setAuthor(firstNonBlank(detail.getAuthor(), comic.getAuthor()));
        comic.setOverview(firstNonBlank(detail.getOverview(), comic.getOverview()));
        comic.setSeries(firstNonBlank(detail.getSeries(), comic.getSeries()));
        if (detail.getSeriesPart() != null) comic.setSeriesPart(detail.getSeriesPart());
        if (detail.getPubYear() != null) comic.setPubYear(detail.getPubYear());
        if (detail.getRating() != null) comic.setRating(detail.getRating());
        comic.setSourceId(firstNonBlank(detail.getSourceId(), comic.getSourceId()));
        comic.setMetadataSource("scrape");

        if (detail.getCoverUrl() != null && !detail.getCoverUrl().isBlank()) {
            downloadCover(comic, detail.getCoverUrl());
        }
    }

    private void downloadCover(Comic comic, String coverUrl) {
        Path comicDir = Files.isDirectory(Paths.get(comic.getBookPath()))
                ? Paths.get(comic.getBookPath())
                : Paths.get(comic.getBookPath()).getParent();
        if (comicDir == null || !Files.isDirectory(comicDir)) return;
        Path target = comicDir.resolve("cover.jpg");
        try {
            byte[] body = scraperRestTemplate.getForObject(coverUrl, byte[].class);
            if (body != null && body.length > 0) {
                Files.write(target, body);
                comic.setCoverArtPath(target.toString());
                log.debug("[ComicScrape] Cover saved: {}", target);
            } else {
                log.warn("[ComicScrape] Cover download failed: empty body");
            }
        } catch (Exception e) {
            log.warn("[ComicScrape] Cover download error: {}", e.getMessage());
        }
    }

    private List<ComicMetadataProvider> providers(String source) {
        if (source != null && !source.isBlank()) {
            ComicMetadataProvider p = providers.get(source);
            if (p == null) throw new BadRequestException("未知数据源: " + source);
            return List.of(p);
        }
        return List.copyOf(providers.values());
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }
}
