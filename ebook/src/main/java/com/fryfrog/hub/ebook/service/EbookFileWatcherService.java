package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.AbstractFileWatcherService;
import com.fryfrog.hub.common.service.MediaLibraryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 电子书模块文件监听服务。
 * <p>
 * 新电子书文件出现后自动触发扫描、整理和刮削。
 */
@Service
@Slf4j
public class EbookFileWatcherService extends AbstractFileWatcherService {

    private static final Set<String> EBOOK_EXTENSIONS = Set.of(
            "epub", "pdf", "mobi", "azw", "azw3", "fb2", "txt"
    );

    private final EbookService ebookService;
    private final EbookMetadataScrapeService scrapeService;
    private final MediaLibraryService mediaLibraryService;

    public EbookFileWatcherService(EbookService ebookService,
                                   EbookMetadataScrapeService scrapeService,
                                   MediaLibraryService mediaLibraryService) {
        this.ebookService = ebookService;
        this.scrapeService = scrapeService;
        this.mediaLibraryService = mediaLibraryService;
    }

    @Override
    protected List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "EBOOK".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return ebookService.getRootPaths();
    }

    @Override
    protected Set<String> getWatchedExtensions() {
        return EBOOK_EXTENSIONS;
    }

    @Override
    protected void processDirectory(Path directory) {
        log.info("[EbookWatcher] Scanning directory: {}", directory);
        ebookService.fixMissingCoverPaths();
        ebookService.scanDirectory(directory.toString());
        ebookService.organizeAll();
        scrapeService.autoScrapeAll();
    }
}
