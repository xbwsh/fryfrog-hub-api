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
    private final EbookService ebookService;
    private final EbookBangumiScrapeService bangumiScrapeService;
    private final BangumiService bangumiService;
    private final OpenLibraryService openLibraryService;
    private final RestTemplate scraperRestTemplate;
    private final ScrapeProgressService scrapeProgressService;

    public Ebook updateMetadata(Long ebookId, Map<String, Object> metadata) {
        Ebook ebook = repository.findById(ebookId)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", ebookId));

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
                .filter(e -> e.getOpenLibraryId() == null)
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
        if (ebook.getBangumiId() != null || ebook.getOpenLibraryId() != null) {
            return ebook;
        }

        // 如果已有 MediaSeries 关联，复用其 bangumiId
        if (ebook.getSeriesRef() != null && ebook.getSeriesRef().getMetadataSourceId() != null) {
            log.info("Reusing bangumiId={} from MediaSeries '{}' for '{}'",
                    ebook.getSeriesRef().getMetadataSourceId(), ebook.getSeriesRef().getTitle(), ebook.getTitle());
            Ebook bound = bangumiScrapeService.bindBangumi(ebook.getId(), ebook.getSeriesRef().getMetadataSourceId());
            log.info("Auto-scraped '{}' from MediaSeries (id={})", bound.getTitle(), ebook.getSeriesRef().getMetadataSourceId());
            return bound;
        }

        // 如果同系列已有绑定的书，复用其 bangumiId
        if (ebook.getSeriesRef() != null) {
            List<Ebook> siblings = repository.findBySeriesRef_Id(ebook.getSeriesRef().getId());
            Integer inheritedBangumiId = siblings.stream()
                    .filter(e -> e.getBangumiId() != null)
                    .map(Ebook::getBangumiId)
                    .findFirst()
                    .orElse(null);
            if (inheritedBangumiId != null) {
                log.info("Reusing bangumiId={} from series '{}' for '{}'",
                        inheritedBangumiId, ebook.getSeries(), ebook.getTitle());
                Ebook bound = bangumiScrapeService.bindBangumi(ebook.getId(), inheritedBangumiId);
                log.info("Auto-scraped '{}' from series sibling (id={})", bound.getTitle(), inheritedBangumiId);
                return bound;
            }
        }

        String query = ebookService.extractSeriesName(ebook);
        if (query.isBlank()) {
            query = ebook.getTitle();
        }

        // 优先 Bangumi（轻小说覆盖更全）
        List<BangumiService.SearchResult> bgmResults = bangumiService.searchBooks(query, "novel");
        if (!bgmResults.isEmpty()) {
            BangumiService.SearchResult best = bgmResults.get(0);
            if (best.getScore() != null && best.getScore() >= 0.0) {
                Ebook bound = bangumiScrapeService.bindBangumi(ebook.getId(), best.getId());
                log.info("Auto-scraped '{}' from Bangumi (id={})", bound.getTitle(), best.getId());
                return bound;
            }
        }

        // 回退 Open Library
        List<OpenLibraryService.SearchResult> olResults = openLibraryService.searchBooks(query);
        if (olResults.isEmpty()) {
            log.debug("No results for '{}' from Bangumi or Open Library", query);
            return ebook;
        }

        OpenLibraryService.SearchResult best = olResults.get(0);
        return scrapeAndBindFromOpenLibrary(ebook.getId(), best);
    }

    @Transactional
    public Ebook scrapeAndBindFromOpenLibrary(Long ebookId, OpenLibraryService.SearchResult result) {
        Ebook ebook = repository.findById(ebookId)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", ebookId));

        if (result.getTitle() != null && !result.getTitle().isBlank()) {
            ebook.setTitle(result.getTitle());
        }
        if (result.getAuthorName() != null && !result.getAuthorName().isBlank()) {
            ebook.setAuthor(result.getAuthorName());
        }
        if (result.getFirstPublishYear() != null) {
            ebook.setYear(result.getFirstPublishYear());
        }
        if (result.getPublishers() != null && !result.getPublishers().isEmpty()) {
            ebook.setPublisher(result.getPublishers().get(0));
        }
        if (result.getIsbns() != null && !result.getIsbns().isEmpty()) {
            ebook.setIsbn(result.getIsbns().get(0));
        }

        String coverUrl = null;
        if (result.getIsbns() != null && !result.getIsbns().isEmpty()) {
            coverUrl = openLibraryService.getCoverUrlByIsbn(result.getIsbns().get(0));
        }
        if (coverUrl == null && result.getOlid() != null) {
            coverUrl = openLibraryService.getCoverUrlByOlid(result.getOlid());
        }

        if (coverUrl != null) {
            String localPath = downloadCover(ebook, coverUrl, "ol_" + result.getOlid());
            if (localPath != null) {
                ebook.setCoverArtPath(localPath);
            } else {
                ebook.setCoverArtPath(coverUrl);
            }
        }

        ebook.setOpenLibraryId(result.getOlid());

        Ebook saved = repository.save(ebook);
        log.info("Successfully bound Open Library metadata to ebook: {} (olid={})", saved.getTitle(), result.getOlid());
        return saved;
    }

    private String downloadCover(Ebook ebook, String coverUrl, String prefix) {
        if (coverUrl == null || coverUrl.isBlank() || ebook.getFilePath() == null) {
            return null;
        }

        try {
            Path ebookDir = Paths.get(ebook.getFilePath()).getParent();
            if (ebookDir == null) return null;
            Files.createDirectories(ebookDir);

            Path coverPath = ebookDir.resolve(prefix + "_cover.jpg");
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
