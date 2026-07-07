package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.service.BangumiService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookMetadataScrapeService {

    private final EbookRepository repository;
    private final BangumiService bangumiService;
    private final EbookService ebookService;
    private final RestTemplate scraperRestTemplate;
    private final ScrapeProgressService scrapeProgressService;

    public List<Map<String, Object>> searchBooks(String keyword) {
        List<BangumiService.SearchResult> results = bangumiService.searchManga(keyword);

        List<Map<String, Object>> books = new ArrayList<>();
        for (BangumiService.SearchResult result : results) {
            Map<String, Object> book = new LinkedHashMap<>();
            book.put("id", result.getId());
            book.put("name", result.getNameCn() != null ? result.getNameCn() : result.getName());
            book.put("nameOriginal", result.getName());
            book.put("summary", result.getSummary());
            book.put("date", result.getDate());
            book.put("score", result.getScore());
            book.put("rank", result.getRank());

            if (result.getImages() != null) {
                String coverUrl = result.getImages().getLarge();
                if (coverUrl == null || coverUrl.isBlank()) {
                    coverUrl = result.getImages().getCommon();
                }
                book.put("coverUrl", coverUrl);
            }

            if (result.getTags() != null) {
                book.put("tags", result.getTags().stream()
                        .map(BangumiService.SearchResult.Tag::getName)
                        .toList());
            }

            books.add(book);
        }

        log.info("Bangumi search for '{}' returned {} results", keyword, books.size());
        return books;
    }

    public Ebook scrapeAndBind(Long ebookId, Integer bangumiId) {
        Ebook ebook = repository.findById(ebookId)
                .orElseThrow(() -> new com.fryfrog.hub.common.exception.ResourceNotFoundException("Ebook", "id", ebookId));

        BangumiService.SubjectDetail detail = bangumiService.getSubjectDetail(bangumiId);
        if (detail == null) {
            log.warn("Bangumi subject not found: {}", bangumiId);
            return ebook;
        }

        // 中文名优先
        if (detail.getNameCn() != null && !detail.getNameCn().isBlank()) {
            ebook.setTitle(detail.getNameCn());
        } else if (detail.getName() != null && !detail.getName().isBlank()) {
            ebook.setTitle(detail.getName());
        }

        // 简介
        if (detail.getSummary() != null && !detail.getSummary().isBlank()) {
            String summary = detail.getSummary();
            ebook.setDescription(summary.length() > 2000 ? summary.substring(0, 2000) : summary);
        }

        // 封面
        if (detail.getImages() != null) {
            String coverUrl = detail.getImages().getLarge();
            if (coverUrl == null || coverUrl.isBlank()) {
                coverUrl = detail.getImages().getCommon();
            }
            if (coverUrl != null && !coverUrl.isBlank()) {
                String localPath = downloadCover(ebook, coverUrl, bangumiId);
                if (localPath != null) {
                    ebook.setCoverArtPath(localPath);
                } else {
                    ebook.setCoverArtPath(coverUrl);
                }
            }
        }

        // 从 infobox 提取作者、出版社、ISBN 等
        if (detail.getInfobox() != null) {
            for (BangumiService.SubjectDetail.InfoboxEntry entry : detail.getInfobox()) {
                String key = entry.getKey();
                String value = entry.getValue() instanceof String s ? s : (entry.getValue() != null ? entry.getValue().toString() : null);
                if (value == null || value.isBlank()) continue;

                switch (key) {
                    case "作者", "原作" -> {
                        if (ebook.getAuthor() == null || ebook.getAuthor().isBlank()) {
                            ebook.setAuthor(value);
                        }
                    }
                    case "出版社" -> {
                        if (ebook.getPublisher() == null || ebook.getPublisher().isBlank()) {
                            ebook.setPublisher(value);
                        }
                    }
                    case "发售日", "出版日期" -> {
                        if (ebook.getYear() == null) {
                            try {
                                ebook.setYear(Integer.parseInt(value.substring(0, 4)));
                            } catch (Exception ignored) {}
                        }
                    }
                    case "ISBN" -> {
                        if (ebook.getIsbn() == null || ebook.getIsbn().isBlank()) {
                            ebook.setIsbn(value);
                        }
                    }
                    case "页数" -> {
                        try {
                            ebook.setPageCount(Integer.parseInt(value.replaceAll("[^0-9]", "")));
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // 标签作为类型
        if (detail.getTags() != null && !detail.getTags().isEmpty() && (ebook.getGenre() == null || ebook.getGenre().isBlank())) {
            ebook.setGenre(detail.getTags().get(0).getName());
        }

        ebook.setBangumiId(bangumiId);

        Ebook saved = repository.save(ebook);
        log.info("Successfully bound Bangumi metadata to ebook: {} (bangumiId={})", saved.getTitle(), bangumiId);
        return saved;
    }

    public Ebook updateMetadata(Long ebookId, Map<String, Object> metadata) {
        Ebook ebook = repository.findById(ebookId)
                .orElseThrow(() -> new com.fryfrog.hub.common.exception.ResourceNotFoundException("Ebook", "id", ebookId));

        if (metadata.containsKey("title") && metadata.get("title") != null) {
            ebook.setTitle(metadata.get("title").toString());
        }
        if (metadata.containsKey("author") && metadata.get("author") != null) {
            ebook.setAuthor(metadata.get("author").toString());
        }
        if (metadata.containsKey("publisher") && metadata.get("publisher") != null) {
            ebook.setPublisher(metadata.get("publisher").toString());
        }
        if (metadata.containsKey("year") && metadata.get("year") != null) {
            try {
                ebook.setYear(Integer.parseInt(metadata.get("year").toString()));
            } catch (NumberFormatException ignored) {}
        }
        if (metadata.containsKey("isbn") && metadata.get("isbn") != null) {
            ebook.setIsbn(metadata.get("isbn").toString());
        }
        if (metadata.containsKey("genre") && metadata.get("genre") != null) {
            ebook.setGenre(metadata.get("genre").toString());
        }
        if (metadata.containsKey("description") && metadata.get("description") != null) {
            ebook.setDescription(metadata.get("description").toString());
        }

        return repository.save(ebook);
    }

    @Async
    public void autoScrapeAll() {
        List<Ebook> unboundEbooks = repository.findAll().stream()
                .filter(e -> e.getBangumiId() == null)
                .toList();

        if (unboundEbooks.isEmpty()) {
            log.debug("No unbound ebooks found for auto-scrape");
            return;
        }

        log.info("Starting auto-scrape for {} ebooks", unboundEbooks.size());
        scrapeProgressService.start("ebook", unboundEbooks.size());

        for (Ebook ebook : unboundEbooks) {
            try {
                scrapeProgressService.updateItem("ebook", ebook.getTitle(), "processing", null);
                autoScrapeEbook(ebook);
                scrapeProgressService.updateItem("ebook", ebook.getTitle(), "completed", null);
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("Failed to auto-scrape ebook '{}': {}", ebook.getTitle(), e.getMessage());
                scrapeProgressService.updateItem("ebook", ebook.getTitle(), "failed", e.getMessage());
            }
        }

        scrapeProgressService.finish("ebook");
        log.info("Auto-scrape completed");
    }

    @Transactional
    public Ebook autoScrapeEbook(Ebook ebook) {
        if (ebook.getBangumiId() != null) {
            return ebook;
        }

        String query = ebookService.extractSeriesName(ebook);
        if (query.isBlank()) {
            query = ebook.getTitle();
        }

        List<BangumiService.SearchResult> results = bangumiService.searchManga(query);
        if (results.isEmpty()) {
            log.debug("No Bangumi results for '{}'", query);
            return ebook;
        }

        BangumiService.SearchResult best = results.get(0);
        return scrapeAndBind(ebook.getId(), best.getId());
    }

    private String downloadCover(Ebook ebook, String coverUrl, Integer bangumiId) {
        if (coverUrl == null || coverUrl.isBlank() || ebook.getFilePath() == null) {
            return null;
        }

        try {
            Path ebookDir = Paths.get(ebook.getFilePath()).getParent();
            if (ebookDir == null) return null;
            Files.createDirectories(ebookDir);

            Path coverPath = ebookDir.resolve("bangumi_" + bangumiId + "_cover.jpg");
            if (Files.exists(coverPath)) {
                return coverPath.toAbsolutePath().toString();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/0.1.0");
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = scraperRestTemplate.exchange(
                    coverUrl, HttpMethod.GET, request, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Files.write(coverPath, response.getBody());
                log.info("Downloaded cover for '{}' to {}", ebook.getTitle(), coverPath);
                return coverPath.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            log.warn("Failed to download cover for '{}': {}", ebook.getTitle(), e.getMessage());
        }
        return null;
    }
}
