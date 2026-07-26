package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.AbstractFileWatcherService;
import com.fryfrog.hub.common.service.MediaLibraryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 漫画模块文件监听服务。
 * <p>
 * 新漫画文件出现后自动触发扫描、整理和刮削。
 */
@Service
@Slf4j
public class ComicFileWatcherService extends AbstractFileWatcherService {

    private static final Set<String> COMIC_EXTENSIONS = Set.of(
            "cbz", "cbr", "zip", "rar", "epub"
    );

    private final ComicMetadataService metadataService;
    private final MangaScrapeService scrapeService;
    private final MediaLibraryService mediaLibraryService;

    public ComicFileWatcherService(ComicMetadataService metadataService,
                                   MangaScrapeService scrapeService,
                                   MediaLibraryService mediaLibraryService) {
        this.metadataService = metadataService;
        this.scrapeService = scrapeService;
        this.mediaLibraryService = mediaLibraryService;
    }

    @Override
    protected List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "COMIC".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return metadataService.getRootPaths();
    }

    @Override
    protected Set<String> getWatchedExtensions() {
        return COMIC_EXTENSIONS;
    }

    @Override
    protected void processDirectory(Path directory) {
        log.info("[ComicWatcher] Scanning directory: {}", directory);
        metadataService.scanDirectory(directory.toString());
        metadataService.organizeAll();
        scrapeService.autoScrapeAll();
    }
}
