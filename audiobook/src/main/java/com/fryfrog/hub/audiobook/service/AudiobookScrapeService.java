package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有声书刮削：搜索候选 → 绑定（结果落 DB，不改音频文件）→ 解绑。
 * 数据源通过 {@link AudiobookMetadataProvider} 可插拔。
 */
@Service
@Slf4j
public class AudiobookScrapeService {

    private final AudiobookRepository bookRepository;
    private final Map<String, AudiobookMetadataProvider> providers;

    public AudiobookScrapeService(AudiobookRepository bookRepository,
                                  List<AudiobookMetadataProvider> providerList) {
        this.bookRepository = bookRepository;
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
    public List<AudiobookScrapeResult> search(String keyword, String source) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("搜索关键词不能为空");
        }
        List<AudiobookScrapeResult> results = new ArrayList<>();
        for (AudiobookMetadataProvider provider : providers(source)) {
            try {
                results.addAll(provider.search(keyword));
            } catch (Exception e) {
                log.warn("[AudiobookScrape] Provider '{}' search failed: {}", provider.source(), e.getMessage());
            }
        }
        return results;
    }

    /** 绑定外部元数据：结果落 DB（不改音频文件），封面下载到书目录。 */
    public Audiobook bind(Long bookId, String source, String sourceId) {
        Audiobook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Audiobook", "id", bookId));
        AudiobookMetadataProvider provider = providers.get(source);
        if (provider == null) {
            throw new BadRequestException("未知数据源: " + source);
        }
        AudiobookScrapeResult detail;
        try {
            detail = provider.fetch(sourceId);
        } catch (Exception e) {
            throw new BadRequestException("获取元数据失败: " + e.getMessage());
        }
        if (detail == null) {
            throw new ResourceNotFoundException("ScrapeResult", "sourceId", sourceId);
        }

        applyScraped(book, detail);
        log.info("[AudiobookScrape] Bound book {} <- {}/{}", bookId, source, sourceId);
        return bookRepository.save(book);
    }

    /** 解绑：清除刮削痕迹，已写入的字段保留。 */
    public Audiobook unbind(Long bookId) {
        Audiobook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Audiobook", "id", bookId));
        book.setSourceId(null);
        book.setMetadataSource("manual");
        return bookRepository.save(book);
    }

    private void applyScraped(Audiobook book, AudiobookScrapeResult detail) {
        book.setTitle(firstNonBlank(detail.getTitle(), book.getTitle()));
        book.setAuthor(firstNonBlank(detail.getAuthor(), book.getAuthor()));
        book.setNarrator(firstNonBlank(detail.getNarrator(), book.getNarrator()));
        book.setOverview(firstNonBlank(detail.getOverview(), book.getOverview()));
        book.setSeries(firstNonBlank(detail.getSeries(), book.getSeries()));
        if (detail.getSeriesPart() != null) book.setSeriesPart(detail.getSeriesPart());
        book.setSourceId(firstNonBlank(detail.getSourceId(), book.getSourceId()));
        book.setMetadataSource("scrape");

        if (detail.getCoverUrl() != null && !detail.getCoverUrl().isBlank()) {
            downloadCover(book, detail.getCoverUrl());
        }
    }

    private void downloadCover(Audiobook book, String coverUrl) {
        Path bookDir = Paths.get(book.getBookPath());
        if (!Files.isDirectory(bookDir)) return;
        Path target = bookDir.resolve("cover.jpg");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(coverUrl))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body().length > 0) {
                Files.write(target, response.body());
                book.setCoverArtPath(target.toString());
                log.debug("[AudiobookScrape] Cover saved: {}", target);
            } else {
                log.warn("[AudiobookScrape] Cover download failed: HTTP {}", response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[AudiobookScrape] Cover download error: {}", e.getMessage());
        }
    }

    private List<AudiobookMetadataProvider> providers(String source) {
        if (source != null && !source.isBlank()) {
            AudiobookMetadataProvider p = providers.get(source);
            if (p == null) throw new BadRequestException("未知数据源: " + source);
            return List.of(p);
        }
        return List.copyOf(providers.values());
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }
}
