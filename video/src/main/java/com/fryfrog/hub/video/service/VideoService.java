package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final VideoSeriesRepository seriesRepository;
    private final VideoScanService scanService;
    private final VideoScrapeService scrapeService;
    private final VideoOrganizeService organizeService;
    private final VideoPipelineService pipelineService;
    private final MediaLibraryService mediaLibraryService;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final VideoAssetService assetService;
    private final ScrapeProgressService progressService;
    private final FavoriteService favoriteService;

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
        if (mediaLibraryService.isRestrictedCurrentUser()) {
            return repository.findAllByAllowedLibraries(mediaLibraryService.getAllowableLibraryIds());
        }
        return repository.findAllByEnabledLibraries(mediaLibraryService.getEnabledLibraryIds());
    }

    public Video getVideoById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Video", "id", id));
    }

    public List<Video> searchByTitle(String title) {
        if (mediaLibraryService.isRestrictedCurrentUser()) {
            return repository.findByTitleContainingIgnoreCaseAndAllowedLibraries(title, mediaLibraryService.getAllowableLibraryIds());
        }
        return repository.findByTitleContainingIgnoreCaseAndEnabledLibraries(title, mediaLibraryService.getEnabledLibraryIds());
    }

    public List<Video> searchByDirector(String director) {
        if (mediaLibraryService.isRestrictedCurrentUser()) {
            return repository.findByDirectorContainingIgnoreCaseAndAllowedLibraries(director, mediaLibraryService.getAllowableLibraryIds());
        }
        return repository.findByDirectorContainingIgnoreCaseAndEnabledLibraries(director, mediaLibraryService.getEnabledLibraryIds());
    }

    public PageResponse<Video> searchByTitle(String title, int page, int size) {
        if (mediaLibraryService.isRestrictedCurrentUser()) {
            var result = repository.findByTitleContainingIgnoreCaseAndAllowedLibraries(title, mediaLibraryService.getAllowableLibraryIds(), PageRequest.of(page, size));
            return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
        }
        var result = repository.findByTitleContainingIgnoreCaseAndEnabledLibraries(title, mediaLibraryService.getEnabledLibraryIds(), PageRequest.of(page, size));
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    public PageResponse<Video> searchByDirector(String director, int page, int size) {
        if (mediaLibraryService.isRestrictedCurrentUser()) {
            var result = repository.findByDirectorContainingIgnoreCaseAndAllowedLibraries(director, mediaLibraryService.getAllowableLibraryIds(), PageRequest.of(page, size));
            return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
        }
        var result = repository.findByDirectorContainingIgnoreCaseAndEnabledLibraries(director, mediaLibraryService.getEnabledLibraryIds(), PageRequest.of(page, size));
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    // ==================== 用户状态 ====================

    public PageResponse<Video> getFavorites(Long userId, int page, int size) {
        List<Long> favIds = favoriteService.contentIds(userId, Favorite.TYPE_VIDEO);
        if (favIds.isEmpty()) {
            return PageResponse.of(List.of(), page, size, 0);
        }
        List<Long> allowedIds = mediaLibraryService.getAllowedLibraryIds(userId);
        List<Video> enabled = repository.findByIdIn(favIds).stream()
                .filter(v -> v.getLibraryId() != null && allowedIds.contains(v.getLibraryId()))
                .sorted(Comparator.comparing(Video::getTitle))
                .toList();
        int start = (int) Math.min(page * (long) size, enabled.size());
        int end = (int) Math.min((page + 1) * (long) size, enabled.size());
        return PageResponse.of(enabled.subList(start, end), page, size, enabled.size());
    }

    public void setFavorite(Long userId, Long id, boolean status) {
        favoriteService.setFavorite(userId, Favorite.TYPE_VIDEO, id, status);
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

    public List<Video> bindSeries(Long videoId, Long tmdbId, String mediaType, boolean isAdult) {
        return scrapeService.bindSeries(videoId, tmdbId, mediaType, isAdult);
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

    // ==================== 成人标记同步 ====================

    /**
     * 同步资源库内所有视频及其所属系列的成人标记
     * @param libraryId 资源库 ID
     * @param adult 是否标记为成人
     * @return 更新的记录数（视频 + 系列）
     */
    @Transactional
    public int syncAdultByLibrary(Long libraryId, boolean adult) {
        List<Video> videos = repository.findByLibraryId(libraryId);
        if (videos.isEmpty()) {
            log.info("[Adult] No videos in library {} to sync, adult={}", libraryId, adult);
            return 0;
        }

        Set<VideoSeries> affectedSeries = new HashSet<>();
        int updatedVideos = 0;
        for (Video video : videos) {
            if (!Boolean.valueOf(adult).equals(video.getIsAdult())) {
                video.setIsAdult(adult);
                updatedVideos++;
            }
            if (video.getSeries() != null) {
                affectedSeries.add(video.getSeries());
            }
        }
        if (updatedVideos > 0) {
            repository.saveAll(videos);
        }

        int updatedSeries = 0;
        for (VideoSeries series : affectedSeries) {
            if (!Boolean.valueOf(adult).equals(series.getIsAdult())) {
                series.setIsAdult(adult);
                seriesRepository.save(series);
                updatedSeries++;
            }
        }

        log.info("[Adult] Synced library {} adult={}: {} videos, {} series", libraryId, adult, updatedVideos, updatedSeries);
        return updatedVideos + updatedSeries;
    }

    // ==================== 扫描/刮削/整理 ====================

    public void scanDirectory(String directoryPath) {
        scanDirectory(directoryPath, null);
    }    public void scanDirectory(String directoryPath, Long libraryId) {
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

}
