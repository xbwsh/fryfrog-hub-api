package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.common.util.DatabaseWriteLock;
import com.fryfrog.hub.common.util.TitleCleaner;
import com.fryfrog.hub.video.dto.TmdbEpisodeDetail;
import com.fryfrog.hub.video.dto.TmdbMovieDetail;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频刮削服务：负责 TMDB 搜索、匹配、元数据绑定。
 * TMDB API 调用在写锁外执行，只在 DB 写入时短暂持有写锁。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoScrapeService {

    private final VideoRepository repository;
    private final TmdbService tmdbService;
    private final NfoService nfoService;
    private final SeriesService seriesService;
    private final VideoActorRepository actorRepository;
    private final TransactionTemplate transactionTemplate;
    private final SystemSettingService settingService;
    private final ScrapeProgressService scrapeProgressService;
    private final MediaLibraryService mediaLibraryService;
    private final VideoScanService scanService;

    private volatile ExecutorService scrapeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // ==================== 查询方法 ====================

    /**
     * 查找未刮削的视频
     */
    public List<Video> findUnscraped(Long libraryId) {
        return scanService.findUnscraped(libraryId);
    }

    // ==================== 自动刮削 ====================

    /**
     * 自动刮削所有未刮削的视频（异步）
     */
    public List<Video> autoScrapeAll() {
        return autoScrapeAll(false);
    }

    /**
     * 自动刮削所有未刮削的视频
     * @param async true 时异步执行（从 API 调用），false 时同步执行
     */
    public List<Video> autoScrapeAll(boolean async) {
        log.info("[Scrape] autoScrapeAll called, async={}", async);
        if (!settingService.getBoolean("scrape.auto-scrape", true)) {
            log.info("[Scrape] Auto-scrape is disabled by setting");
            return repository.findAll();
        }

        List<Video> videos = scanService.findUnscraped(null);
        log.info("[Scrape] Found {} unscraped videos", videos.size());
        if (videos.isEmpty()) {
            return repository.findAll();
        }

        Runnable scrapeTask = () -> executeScrapeBatch(videos);

        if (async) {
            scrapeExecutor.submit(scrapeTask);
        } else {
            scrapeTask.run();
        }

        return repository.findAll();
    }

    /**
     * 批量刮削指定视频列表
     */
    public void batchScrapeAndBind(List<Video> videos) {
        executeScrapeBatch(videos);
    }

    private void executeScrapeBatch(List<Video> videos) {
        java.util.concurrent.atomic.AtomicInteger scraped = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger(0);
        scrapeProgressService.start("video", videos.size());

        // 按系列分组，优先处理同一系列的内容
        List<List<Video>> seriesGroups = groupVideosBySeries(videos);
        log.debug("[Scrape] Grouped {} videos into {} series groups", videos.size(), seriesGroups.size());

        for (List<Video> seriesGroup : seriesGroups) {
            if (seriesGroup.isEmpty()) continue;

            String seriesKey = getSeriesKey(seriesGroup.get(0));
            log.debug("[Scrape] Processing series group '{}' ({} videos)", seriesKey, seriesGroup.size());

            for (Video video : seriesGroup) {
                try {
                    String query = video.getTitle();
                    if (query == null || query.isBlank()) {
                        scrapeProgressService.updateItem("video", video.getFileName(), "skipped", "empty title");
                        continue;
                    }

                    // 检查是否需要重新刮削
                    if (video.getTmdbId() != null && !isMetadataComplete(video)) {
                        log.info("[Scrape] Metadata incomplete for '{}', re-scraping", video.getTitle());
                        unbindTmdb(video.getId());
                        video.setTmdbId(null);
                    }

                    String mediaTypeFilter = resolveMediaTypeFilter(video);

                    scrapeProgressService.updateItem("video", video.getFileName(), "processing", null);

                    // Phase 1: TMDB 搜索（无锁）
                    List<TmdbSearchResult.TmdbSearchItem> results = searchFromTmdb(query, mediaTypeFilter);
                    if (results.isEmpty()) {
                        log.debug("[Scrape] No TMDB results for: '{}'", video.getTitle());
                        markScrapeAttempted(video);
                        scrapeProgressService.updateItem("video", video.getFileName(), "failed", "no TMDB results");
                        continue;
                    }

                    TmdbSearchResult.TmdbSearchItem bestMatch = pickBestTmdbMatch(results, query);
                    if (bestMatch == null) {
                        log.debug("[Scrape] No confident TMDB match for: '{}'", video.getTitle());
                        markScrapeAttempted(video);
                        scrapeProgressService.updateItem("video", video.getFileName(), "failed", "no confident match");
                        continue;
                    }

                    boolean isAdult = Boolean.TRUE.equals(bestMatch.getAdult());

                    // Phase 2: 获取 TMDB 详情（无锁）
                    Object detail = null;
                    if ("movie".equalsIgnoreCase(bestMatch.getMediaType())) {
                        detail = tmdbService.getMovieDetail(bestMatch.getId());
                    } else if ("tv".equalsIgnoreCase(bestMatch.getMediaType())) {
                        detail = tmdbService.getTvDetail(bestMatch.getId());
                    }

                    if (detail == null) {
                        log.warn("[Scrape] Failed to fetch TMDB detail for: {} (tmdbId={})", video.getTitle(), bestMatch.getId());
                        markScrapeAttempted(video);
                        scrapeProgressService.updateItem("video", video.getFileName(), "failed", "TMDB detail fetch failed");
                        continue;
                    }

                    // Phase 3: 绑定元数据（写锁内）
                    doScrapeAndBind(video.getId(), bestMatch.getId(), bestMatch.getMediaType(), isAdult);
                    scraped.incrementAndGet();
                    scrapeProgressService.updateItem("video", video.getFileName(), "completed", null);

                    log.debug("[Scrape] Auto-scraped: {} -> TMDB {} ({})", video.getTitle(), bestMatch.getId(), bestMatch.getMediaType());
                } catch (Exception e) {
                    log.warn("[Scrape] Failed to auto-scrape video {}: {}", video.getTitle(), e.getMessage());
                    markScrapeAttempted(video);
                    failed.incrementAndGet();
                    scrapeProgressService.updateItem("video", video.getFileName(), "failed", e.getMessage());
                }
            }
            log.debug("[Scrape] Completed series group '{}' ({} videos)", seriesKey, seriesGroup.size());
        }

        scrapeProgressService.finish("video");
        log.info("[Scrape] Auto-scrape completed: {}/{} scraped, {} failed", scraped.get(), videos.size(), failed.get());
    }

    // ==================== TMDB 搜索 ====================

    /**
     * 搜索 TMDB（电影 + 电视剧）
     */
    public List<TmdbSearchResult.TmdbSearchItem> searchFromTmdb(String query) {
        return searchFromTmdb(query, null);
    }

    /**
     * 搜索 TMDB（可指定 mediaType 过滤）
     */
    public List<TmdbSearchResult.TmdbSearchItem> searchFromTmdb(String query, String mediaTypeFilter) {
        if (!tmdbService.isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cleanedQuery = TitleCleaner.cleanForSearch(query);

        List<TmdbSearchResult.TmdbSearchItem> movieResults = List.of();
        List<TmdbSearchResult.TmdbSearchItem> tvResults = List.of();

        if (mediaTypeFilter == null || "movie".equalsIgnoreCase(mediaTypeFilter)) {
            movieResults = tmdbService.searchMovies(cleanedQuery);
        }
        if (mediaTypeFilter == null || "tv".equalsIgnoreCase(mediaTypeFilter)) {
            tvResults = tmdbService.searchTv(cleanedQuery);
        }

        // 如果清洗后的查询无结果，尝试原始查询
        if (movieResults.isEmpty() && tvResults.isEmpty() && !cleanedQuery.equals(query)) {
            log.info("[Scrape] No results for cleaned query '{}', trying original: '{}'", cleanedQuery, query);
            if (mediaTypeFilter == null || "movie".equalsIgnoreCase(mediaTypeFilter)) {
                movieResults = tmdbService.searchMovies(query);
            }
            if (mediaTypeFilter == null || "tv".equalsIgnoreCase(mediaTypeFilter)) {
                tvResults = tmdbService.searchTv(query);
            }
        }

        List<TmdbSearchResult.TmdbSearchItem> allResults = new ArrayList<>(movieResults);
        allResults.addAll(tvResults);
        allResults.sort((a, b) -> {
            double scoreA = a.getVoteAverage() != null ? a.getVoteAverage() : 0;
            double scoreB = b.getVoteAverage() != null ? b.getVoteAverage() : 0;
            return Double.compare(scoreB, scoreA);
        });

        return allResults;
    }

    /**
     * 选择最佳 TMDB 匹配
     */
    public TmdbSearchResult.TmdbSearchItem pickBestTmdbMatch(
            List<TmdbSearchResult.TmdbSearchItem> results, String query) {
        String cleanedQuery = TitleCleaner.cleanForSearch(query);

        // 1. 精确匹配 title 或 original_title
        for (var r : results) {
            String name = r.getTitle();
            String originalName = r.getOriginalTitle();
            if (name != null && (name.equals(cleanedQuery) || name.equals(query))) {
                return r;
            }
            if (originalName != null && (originalName.equals(cleanedQuery) || originalName.equals(query))) {
                return r;
            }
        }

        // 2. 相似度匹配
        double bestScore = 0;
        TmdbSearchResult.TmdbSearchItem best = null;
        for (var r : results) {
            String name = r.getTitle();
            String originalName = r.getOriginalTitle();
            double score = 0;
            if (name != null) {
                score = Math.max(score, TitleCleaner.calculateSimilarity(cleanedQuery, name));
            }
            if (originalName != null) {
                score = Math.max(score, TitleCleaner.calculateSimilarity(cleanedQuery, originalName));
            }
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }

        if (best != null && bestScore >= 0.6) {
            return best;
        }
        return null;
    }

    // ==================== TMDB 绑定 ====================

    /**
     * 绑定 TMDB 元数据到视频（写锁内）
     */
    public Video scrapeAndBindTmdb(Long videoId, Long tmdbId, String mediaType) {
        return scrapeAndBindTmdb(videoId, tmdbId, mediaType, false);
    }

    /**
     * 绑定 TMDB 元数据到视频（写锁内）
     */
    public Video scrapeAndBindTmdb(Long videoId, Long tmdbId, String mediaType, boolean isAdult) {
        DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doScrapeAndBind(videoId, tmdbId, mediaType, isAdult));
        } finally {
            DatabaseWriteLock.unlock();
        }
    }

    @Transactional
    Video doScrapeAndBind(Long videoId, Long tmdbId, String mediaType, boolean isAdult) {
        Video video = repository.findById(videoId)
                .orElseThrow(() -> new ResourceNotFoundException("Video", "id", videoId));

        if ("movie".equalsIgnoreCase(mediaType)) {
            TmdbMovieDetail movieDetail = tmdbService.getMovieDetail(tmdbId);
            if (movieDetail == null) {
                throw new ResourceNotFoundException("TMDB Movie", "id", tmdbId);
            }
            updateVideoFromMovieDetail(video, movieDetail);
            video.setTmdbId(tmdbId);
            video.setMediaType(mediaType);
            video.setMetadataSource("tmdb");
            video.setMetadataUpdatedAt(LocalDateTime.now());
            if (isAdult) video.setIsAdult(true);

            repository.save(video);
            return video;

        } else if ("tv".equalsIgnoreCase(mediaType)) {
            TmdbTvDetail tvDetail = tmdbService.getTvDetail(tmdbId);
            if (tvDetail == null) {
                throw new ResourceNotFoundException("TMDB TV", "id", tmdbId);
            }
            updateVideoFromTvDetail(video, tvDetail);

            int[] seasonEpisode = scanService.parseSeasonEpisode(video.getFileName());
            video.setSeasonNumber(seasonEpisode[0]);
            video.setEpisodeNumber(seasonEpisode[1]);

            // 获取剧集详情
            TmdbEpisodeDetail episodeDetail = tmdbService.getTvEpisodeDetail(tmdbId, seasonEpisode[0], seasonEpisode[1]);
            if (episodeDetail != null) {
                if (episodeDetail.getOverview() != null && !episodeDetail.getOverview().isBlank()) {
                    video.setOverview(episodeDetail.getOverview());
                }
                if (episodeDetail.getVoteAverage() != null) {
                    video.setRating(episodeDetail.getVoteAverage());
                }
                if (episodeDetail.getVoteCount() != null) {
                    video.setVoteCount(episodeDetail.getVoteCount());
                }
                if (episodeDetail.getYear() != null) {
                    video.setYear(episodeDetail.getYear());
                }
                if (episodeDetail.getStillPath() != null && !episodeDetail.getStillPath().isBlank()) {
                    video.setBackdropUrl(tmdbService.getBackdropUrl(episodeDetail.getStillPath()));
                }
            }

            // 创建/绑定系列
            VideoSeries series = seriesService.getOrCreateAndBindSeries(tvDetail.getName(), tmdbId);
            seriesService.assignVideoToSeries(video, series);
            if (isAdult) series.setIsAdult(true);

            video.setTmdbId(tmdbId);
            video.setMediaType(mediaType);
            video.setMetadataSource("tmdb");
            video.setMetadataUpdatedAt(LocalDateTime.now());
            if (isAdult) video.setIsAdult(true);

            repository.save(video);
            return video;

        } else {
            throw new IllegalArgumentException("Invalid media type: " + mediaType);
        }
    }

    // ==================== 解绑 ====================

    /**
     * 解绑 TMDB 元数据（写锁内）
     */
    public Video unbindTmdb(Long videoId) {
        DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doUnbindTmdb(videoId));
        } finally {
            DatabaseWriteLock.unlock();
        }
    }

    @Transactional
    Video doUnbindTmdb(Long videoId) {
        Video video = repository.findById(videoId)
                .orElseThrow(() -> new ResourceNotFoundException("Video", "id", videoId));

        if (video.getTmdbId() == null) {
            throw new IllegalStateException("Video is not bound to TMDB");
        }

        log.info("[Scrape] Unbinding TMDB from video: {} (tmdbId={})", video.getTitle(), video.getTmdbId());

        // 清理关联文件
        cleanupUnboundFiles(video);
        cleanupUnboundActors(video);

        // 清除元数据
        video.setTmdbId(null);
        video.setMediaType(null);
        video.setMetadataSource(null);
        video.setOriginalTitle(null);
        video.setOverview(null);
        video.setPosterUrl(null);
        video.setBackdropUrl(null);
        video.setImdbId(null);
        video.setRating(null);
        video.setVoteCount(null);
        video.setDirector(null);
        video.setActors(null);
        video.setGenre(null);
        video.setStatus(null);
        video.setMetadataUpdatedAt(null);

        if (video.getSeries() != null) {
            seriesService.removeVideoFromSeries(video);
        }

        return repository.save(video);
    }

    /**
     * 按 tmdbId 批量解绑
     */
    public int unbindByTmdbId(Long tmdbId) {
        DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> {
                List<Video> videos = repository.findAllByTmdbId(tmdbId);
                int count = 0;
                for (Video video : videos) {
                    cleanupUnboundFiles(video);
                    cleanupUnboundActors(video);

                    video.setTmdbId(null);
                    video.setMediaType(null);
                    video.setMetadataSource(null);
                    video.setOriginalTitle(null);
                    video.setOverview(null);
                    video.setPosterUrl(null);
                    video.setBackdropUrl(null);
                    video.setImdbId(null);
                    video.setRating(null);
                    video.setVoteCount(null);
                    video.setDirector(null);
                    video.setActors(null);
                    video.setGenre(null);
                    video.setStatus(null);
                    video.setMetadataUpdatedAt(null);

                    if (video.getSeries() != null) {
                        seriesService.removeVideoFromSeries(video);
                    }

                    repository.save(video);
                    count++;
                    log.info("[Scrape] Unbinding TMDB from video: {} (tmdbId={})", video.getTitle(), tmdbId);
                }
                return count;
            });
        } finally {
            DatabaseWriteLock.unlock();
        }
    }

    // ==================== 重新刮削 ====================

    /**
     * 重新刮削单个视频所属系列的所有视频
     */
    public List<Video> rescrapeVideo(Long videoId) {
        Video video = repository.findById(videoId)
                .orElseThrow(() -> new ResourceNotFoundException("Video", "id", videoId));

        if (video.getTmdbId() == null || video.getMediaType() == null) {
            throw new IllegalArgumentException("Video has no TMDB binding: " + videoId);
        }

        Long tmdbId = video.getTmdbId();
        String mediaType = video.getMediaType();

        List<Video> siblings = repository.findAllByTmdbId(tmdbId);
        if (siblings.isEmpty()) {
            throw new IllegalArgumentException("No videos found with tmdbId: " + tmdbId);
        }

        log.info("[Scrape] Rescraping {} videos with tmdbId={}", siblings.size(), tmdbId);

        // 先解绑所有
        for (Video s : siblings) {
            try {
                unbindTmdb(s.getId());
            } catch (Exception e) {
                log.warn("[Scrape] Failed to unbind video {} during rescrape: {}", s.getTitle(), e.getMessage());
            }
        }

        // 再重新绑定
        List<Video> results = new ArrayList<>();
        for (Video s : siblings) {
            try {
                Video bound = scrapeAndBindTmdb(s.getId(), tmdbId, mediaType);
                results.add(bound);
            } catch (Exception e) {
                log.warn("[Scrape] Failed to rescrape video {}: {}", s.getTitle(), e.getMessage());
                results.add(s);
            }
        }
        return results;
    }

    /**
     * 重新刮削指定库的所有视频
     */
    public void rescrapeByLibrary(Long libraryId) {
        log.info("[Scrape] rescrapeByLibrary called with libraryId={}", libraryId);

        // Step 1: 解绑库中所有已绑定的视频
        List<Video> allVideos = repository.findAll();
        int bound = 0;
        for (Video video : allVideos) {
            if (libraryId != null && !libraryId.equals(video.getLibraryId())) {
                continue;
            }
            if (video.getTmdbId() != null) {
                try {
                    unbindTmdb(video.getId());
                    bound++;
                } catch (Exception e) {
                    log.warn("[Scrape] Failed to unbind video {}: {}", video.getTitle(), e.getMessage());
                }
            }
        }
        if (bound > 0) {
            log.info("[Scrape] Unbound {} videos from library {}, starting re-scrape", bound, libraryId);
        }

        // Step 2: 重新扫描库
        MediaLibrary library = mediaLibraryService.getLibraryById(libraryId);
        scanService.scanAndSave(library.getPath(), libraryId);

        // Step 3: 重新刮削
        autoScrapeAll(false);
    }

    // ==================== 元数据更新 ====================

    private void updateVideoFromMovieDetail(Video video, TmdbMovieDetail detail) {
        video.setTitle(detail.getTitle());
        video.setOriginalTitle(detail.getOriginalTitle());
        video.setOverview(detail.getOverview());
        video.setYear(detail.getYear());
        video.setDirector(detail.getDirector());
        video.setActors(detail.getActors());
        video.setGenre(detail.getGenres());
        video.setImdbId(detail.getImdbId());
        video.setRating(detail.getVoteAverage());
        video.setVoteCount(detail.getVoteCount());
        video.setPosterUrl(tmdbService.getPosterUrl(detail.getPosterPath()));
        video.setBackdropUrl(tmdbService.getBackdropUrl(detail.getBackdropPath()));
        if (detail.getRuntime() != null) {
            video.setDurationMinutes(detail.getRuntime());
        }
    }

    private void updateVideoFromTvDetail(Video video, TmdbTvDetail detail) {
        video.setTitle(detail.getName());
        video.setOriginalTitle(detail.getOriginalName());
        video.setOverview(detail.getOverview());
        video.setYear(detail.getYear());
        video.setDirector(detail.getDirector());
        video.setActors(detail.getActors());
        video.setGenre(detail.getGenres());
        video.setRating(detail.getVoteAverage());
        video.setVoteCount(detail.getVoteCount());
        video.setStatus(detail.getStatus());
        video.setPosterUrl(tmdbService.getPosterUrl(detail.getPosterPath()));
        video.setBackdropUrl(tmdbService.getBackdropUrl(detail.getBackdropPath()));
    }

    // ==================== 辅助方法 ====================

    private String resolveMediaTypeFilter(Video video) {
        if (video.getLibraryId() != null) {
            MediaLibrary library = mediaLibraryService.getLibraryById(video.getLibraryId());
            String filter = library.getMediaTypeFilter();
            if (filter != null) {
                log.debug("[Scrape] Library filter for '{}': libraryId={}, filter={}", video.getTitle(), video.getLibraryId(), filter);
                return filter;
            }
        }

        if (scanService.isTvEpisode(video)) {
            log.debug("[Scrape] File '{}' parsed as TV episode, forcing mediaType=tv", video.getFileName());
            return "tv";
        }

        return null;
    }

    private boolean isMetadataComplete(Video video) {
        if (video.getPosterUrl() == null || video.getPosterUrl().isBlank()) return false;
        if (video.getBackdropUrl() == null || video.getBackdropUrl().isBlank()) return false;
        if (video.getOverview() == null || video.getOverview().isBlank()) return false;
        return true;
    }

    private void markScrapeAttempted(Video video) {
        try {
            video.setScrapeAttemptedAt(LocalDateTime.now());
            repository.save(video);
        } catch (Exception e) {
            log.debug("[Scrape] Failed to mark scrape attempt for video {}: {}", video.getId(), e.getMessage());
        }
    }

    private void cleanupUnboundFiles(Video video) {
        try {
            var nfoPath = nfoService.getNfoPath(video);
            java.nio.file.Files.deleteIfExists(nfoPath);
        } catch (Exception e) {
            log.debug("[Scrape] Failed to delete NFO for video {}: {}", video.getId(), e.getMessage());
        }
        try {
            var posterPath = nfoService.getPosterPath(video);
            java.nio.file.Files.deleteIfExists(posterPath);
        } catch (Exception e) {
            log.debug("[Scrape] Failed to delete poster for video {}: {}", video.getId(), e.getMessage());
        }
        try {
            var fanartPath = nfoService.getFanartPath(video);
            java.nio.file.Files.deleteIfExists(fanartPath);
        } catch (Exception e) {
            log.debug("[Scrape] Failed to delete fanart for video {}: {}", video.getId(), e.getMessage());
        }
    }

    private void cleanupUnboundActors(Video video) {
        try {
            var actors = actorRepository.findByVideo_Id(video.getId());
            if (!actors.isEmpty()) {
                actorRepository.deleteAll(actors);
                log.debug("[Scrape] Deleted {} actors for video {}", actors.size(), video.getId());
            }
        } catch (Exception e) {
            log.debug("[Scrape] Failed to delete actors for video {}: {}", video.getId(), e.getMessage());
        }
    }

    /**
     * 按系列分组视频
     */
    private List<List<Video>> groupVideosBySeries(List<Video> videos) {
        Map<String, List<Video>> grouped = new LinkedHashMap<>();
        for (Video video : videos) {
            String key = getSeriesKey(video);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(video);
        }
        List<List<Video>> result = new ArrayList<>(grouped.values());
        result.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return result;
    }

    private String getSeriesKey(Video video) {
        if (video.getSeries() != null) {
            return "series:" + video.getSeries().getId();
        }
        String title = video.getTitle();
        if (title == null || title.isBlank()) {
            return "unknown:" + video.getId();
        }
        return "title:" + TitleCleaner.cleanForSearch(title);
    }
}
