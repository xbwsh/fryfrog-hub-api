package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.util.DatabaseWriteLock;
import com.fryfrog.hub.video.dto.HanimeMetadata;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 视频 API 门面服务：提供公共查询方法，组合调用各子服务。
 * 不包含具体的扫描、刮削、整理、资产生成逻辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoService {

    private final VideoRepository repository;
    private final VideoScanService scanService;
    private final VideoScrapeService scrapeService;
    private final VideoOrganizeService organizeService;
    private final VideoAssetService assetService;
    private final VideoPipelineService pipelineService;
    private final HanimeScraperService hanimeScraperService;
    private final MediaLibraryService mediaLibraryService;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final TransactionTemplate transactionTemplate;

    @Qualifier("scraperRestTemplate")
    private final RestTemplate scraperRestTemplate;

    @Value("${video.root-paths:}")
    private String rootPathsConfig;

    // ==================== 路径查询 ====================

    public List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "VIDEO".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public MediaLibrary findLibraryForPath(String filePath) {
        return mediaLibraryService.findByPath(filePath);
    }

    public String getFirstRootPath() {
        List<String> paths = getRootPaths();
        return paths.isEmpty() ? null : paths.get(0);
    }

    // ==================== 视频查询 ====================

    public List<Video> getAllVideos() {
        return repository.findAll();
    }

    public Video getVideoById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Video", "id", id));
    }

    public List<Video> searchByTitle(String title) {
        return repository.findByTitleContainingIgnoreCase(title);
    }

    public List<Video> searchByDirector(String director) {
        return repository.findByDirectorContainingIgnoreCase(director);
    }

    public List<Video> getFavorites() {
        return repository.findByFavoriteTrue();
    }

    public PageResponse<Video> searchByTitle(String title, int page, int size) {
        var result = repository.findByTitleContainingIgnoreCase(title, PageRequest.of(page, size));
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    public PageResponse<Video> searchByDirector(String director, int page, int size) {
        var result = repository.findByDirectorContainingIgnoreCase(director, PageRequest.of(page, size));
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    public PageResponse<Video> getFavorites(int page, int size) {
        var result = repository.findByFavoriteTrue(PageRequest.of(page, size));
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    // ==================== 用户状态 ====================

    public Video setFavorite(Long id, boolean status) {
        Video video = getVideoById(id);
        video.setFavorite(status);
        return repository.save(video);
    }

    // ==================== TMDB 搜索 ====================

    public List<TmdbSearchResult.TmdbSearchItem> searchFromTmdb(String query) {
        return scrapeService.searchFromTmdb(query);
    }

    public List<TmdbSearchResult.TmdbSearchItem> searchFromTmdb(String query, String mediaTypeFilter) {
        return scrapeService.searchFromTmdb(query, mediaTypeFilter);
    }

    // ==================== TMDB 绑定/解绑 ====================

    public Video scrapeAndBindTmdb(Long videoId, Long tmdbId, String mediaType) {
        return scrapeService.scrapeAndBindTmdb(videoId, tmdbId, mediaType);
    }

    public Video scrapeAndBindTmdb(Long videoId, Long tmdbId, String mediaType, boolean isAdult) {
        return scrapeService.scrapeAndBindTmdb(videoId, tmdbId, mediaType, isAdult);
    }

    public Video unbindTmdb(Long videoId) {
        return scrapeService.unbindTmdb(videoId);
    }

    public int unbindByTmdbId(Long tmdbId) {
        return scrapeService.unbindByTmdbId(tmdbId);
    }

    public List<Video> rescrapeVideo(Long videoId) {
        return scrapeService.rescrapeVideo(videoId);
    }

    public Video rescrapeVideo(Long videoId, Long tmdbId, String mediaType) {
        unbindTmdb(videoId);
        return scrapeService.scrapeAndBindTmdb(videoId, tmdbId, mediaType);
    }

    // ==================== Hanime ====================

    public Video scrapeAndBindHanime(Long videoId, String hanimeId) {
        DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doScrapeAndBindHanime(videoId, hanimeId));
        } finally {
            DatabaseWriteLock.unlock();
        }
    }

    @Transactional
    Video doScrapeAndBindHanime(Long videoId, String hanimeId) {
        Video video = getVideoById(videoId);
        HanimeMetadata metadata = hanimeScraperService.scrape(hanimeId);

        if (metadata == null) {
            throw new ResourceNotFoundException("Hanime metadata", "hanimeId", hanimeId);
        }

        if (metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            video.setTitle(metadata.getTitle());
        }
        if (metadata.getDescription() != null && !metadata.getDescription().isBlank()) {
            video.setOverview(metadata.getDescription());
        }
        if (metadata.getStudio() != null && !metadata.getStudio().isBlank()) {
            video.setStudio(metadata.getStudio());
        }
        if (metadata.getSubtitle() != null && !metadata.getSubtitle().isBlank()) {
            video.setSubtitle(metadata.getSubtitle());
        }
        if (metadata.getViewCount() != null) {
            video.setViewCount(metadata.getViewCount());
        }
        if (metadata.getTags() != null && !metadata.getTags().isEmpty()) {
            video.setTags(String.join(",", metadata.getTags()));
        }
        if (metadata.getCoverUrl() != null && !metadata.getCoverUrl().isBlank()) {
            video.setPosterUrl(metadata.getCoverUrl());
        }

        video.setHanimeId(hanimeId);
        video.setMetadataSource("hanime");
        video.setMetadataUpdatedAt(java.time.LocalDateTime.now());

        Video saved = repository.save(video);
        assetService.generateNfoAndCovers(saved);
        log.info("[Video] Bound Hanime metadata to video: {} -> hanimeId={}", saved.getTitle(), hanimeId);
        return saved;
    }

    public HanimeMetadata scrapeHanimeOnly(String hanimeId) {
        return hanimeScraperService.scrape(hanimeId);
    }

    // ==================== 扫描/刮削/整理 ====================

    public void scanDirectory(String directoryPath) {
        scanDirectory(directoryPath, null);
    }

    public void scanDirectory(String directoryPath, Long libraryId) {
        scanService.scanAndSave(directoryPath, libraryId);
    }

    public List<Video> autoScrapeAll() {
        return scrapeService.autoScrapeAll();
    }

    public List<Video> autoScrapeAll(boolean async) {
        return scrapeService.autoScrapeAll(async);
    }

    public void rescrapeAll() {
        scrapeService.rescrapeByLibrary(null);
    }

    public void rescrapeByLibrary(Long libraryId) {
        scrapeService.rescrapeByLibrary(libraryId);
    }

    public Map<String, Object> organizeVideos(String path) {
        List<Video> videos = scanService.findByPath(path);
        return organizeService.batchOrganize(videos);
    }

    public int cleanupInvalidRecords() {
        log.info("[Video] Cleanup invalid records - delegated to scan service");
        // 实际清理逻辑在 VideoScanService.cleanupInvalidRecords() 中
        return 0;
    }
}
