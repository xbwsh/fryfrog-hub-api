package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.util.TitleCleaner;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.video.dto.HanimeMetadata;
import com.fryfrog.hub.video.dto.TmdbEpisodeDetail;
import com.fryfrog.hub.video.dto.TmdbMovieDetail;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class VideoService {

    private final VideoRepository repository;
    private final TmdbService tmdbService;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final SeriesService seriesService;
    private final TransactionTemplate transactionTemplate;
    private final SystemSettingService settingService;
    private final VideoActorRepository actorRepository;
    private final com.fryfrog.hub.video.repository.WatchProgressRepository watchProgressRepository;
    @Qualifier("scraperRestTemplate")
    private final RestTemplate scraperRestTemplate;
    private final ScrapeProgressService scrapeProgressService;
    private final MediaInfoService mediaInfoService;
    private final jakarta.persistence.EntityManager entityManager;
    private final MediaLibraryService mediaLibraryService;
    private final HanimeScraperService hanimeScraperService;

    private volatile ExecutorService scrapeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${hub.video.root-paths:}")
    private String rootPathsConfig;

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
        return paths.isEmpty() ? null : Paths.get(paths.get(0)).toAbsolutePath().normalize().toString();
    }

    private boolean isTmdbConfigured() {
        String apiKey = settingService.getValue("tmdb.api-key", "");
        return apiKey != null && !apiKey.isBlank();
    }

    private static final Set<String> SUPPORTED_FORMATS = Set.of("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v");

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

    public Video setFavorite(Long id, boolean status) {
        Video video = getVideoById(id);
        video.setFavorite(status);
        return repository.save(video);
    }

    public Video unbindTmdb(Long videoId) {
        com.fryfrog.hub.common.util.DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doUnbindTmdb(videoId));
        } finally {
            com.fryfrog.hub.common.util.DatabaseWriteLock.unlock();
        }
    }

    @Transactional
    Video doUnbindTmdb(Long videoId) {
        Video video = getVideoById(videoId);

        if (video.getTmdbId() == null) {
            throw new IllegalStateException("Video is not bound to TMDB");
        }

        log.info("Unbinding TMDB from video: {} (tmdbId={})", video.getTitle(), video.getTmdbId());

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

        return repository.save(video);
    }

    private void cleanupUnboundFiles(Video video) {
        try {
            Path nfoPath = nfoService.getNfoPath(video);
            Files.deleteIfExists(nfoPath);
        } catch (Exception e) {
            log.debug("Failed to delete NFO for video {}: {}", video.getId(), e.getMessage());
        }
        try {
            Path posterPath = nfoService.getPosterPath(video);
            Files.deleteIfExists(posterPath);
        } catch (Exception e) {
            log.debug("Failed to delete poster for video {}: {}", video.getId(), e.getMessage());
        }
        try {
            Path fanartPath = nfoService.getFanartPath(video);
            Files.deleteIfExists(fanartPath);
        } catch (Exception e) {
            log.debug("Failed to delete fanart for video {}: {}", video.getId(), e.getMessage());
        }
    }

    private void cleanupUnboundActors(Video video) {
        try {
            List<VideoActor> actors = actorRepository.findByVideo_Id(video.getId());
            if (!actors.isEmpty()) {
                actorRepository.deleteAll(actors);
                log.debug("Deleted {} actors for video {}", actors.size(), video.getId());
            }
        } catch (Exception e) {
            log.debug("Failed to delete actors for video {}: {}", video.getId(), e.getMessage());
        }
    }

    public int unbindByTmdbId(Long tmdbId) {
        com.fryfrog.hub.common.util.DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doUnbindByTmdbId(tmdbId));
        } finally {
            com.fryfrog.hub.common.util.DatabaseWriteLock.unlock();
        }
    }

    @Transactional
    int doUnbindByTmdbId(Long tmdbId) {
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
            log.info("Unbinding TMDB from video: {} (tmdbId={})", video.getTitle(), tmdbId);
        }
        return count;
    }

    @Transactional
    public Video rescrapeVideo(Long videoId, Long tmdbId, String mediaType) {
        unbindTmdb(videoId);
        return scrapeAndBindTmdb(videoId, tmdbId, mediaType);
    }

    public List<Video> rescrapeVideo(Long videoId) {
        Video video = getVideoById(videoId);
        if (video.getTmdbId() == null || video.getMediaType() == null) {
            throw new IllegalArgumentException("Video has no TMDB binding: " + videoId);
        }
        Long tmdbId = video.getTmdbId();
        String mediaType = video.getMediaType();

        List<Video> siblings = repository.findAllByTmdbId(tmdbId);
        if (siblings.isEmpty()) {
            throw new IllegalArgumentException("No videos found with tmdbId: " + tmdbId);
        }

        log.info("Rescraping {} videos with tmdbId={}", siblings.size(), tmdbId);
        for (Video s : siblings) {
            try {
                unbindTmdb(s.getId());
            } catch (Exception e) {
                log.warn("Failed to unbind video {} during rescrape: {}", s.getTitle(), e.getMessage());
            }
        }

        List<Video> results = new ArrayList<>();
        for (Video s : siblings) {
            try {
                Video bound = scrapeAndBindTmdb(s.getId(), tmdbId, mediaType);
                results.add(bound);
            } catch (Exception e) {
                log.warn("Failed to rescrape video {}: {}", s.getTitle(), e.getMessage());
                results.add(s);
            }
        }
        return results;
    }

    public int cleanupInvalidRecords() {
        int removed = 0;
        int pageNum = 0;
        final int pageSize = 100;
        org.springframework.data.domain.Page<Video> page;

        do {
            page = repository.findAll(org.springframework.data.domain.PageRequest.of(pageNum++, pageSize));
            List<Video> invalidVideos = page.getContent().stream()
                    .filter(v -> v.getFilePath() == null || !Files.exists(Paths.get(v.getFilePath())))
                    .toList();

            if (!invalidVideos.isEmpty()) {
                removed += transactionTemplate.execute(status -> {
                    for (Video video : invalidVideos) {
                        if (video.getSeries() != null) {
                            seriesService.removeVideoFromSeries(video);
                        }
                    }
                    // Video 上有 cascade = CascadeType.REMOVE，删除 Video 时自动级联删除 actor 和 watchProgress
                    repository.deleteAllById(invalidVideos.stream().map(Video::getId).toList());
                    entityManager.clear();
                    return invalidVideos.size();
                });
            }
        } while (page.hasNext());
        seriesService.cleanupEmptySeries();

        if (removed > 0) {
            log.info("Cleanup completed: removed {} invalid records", removed);
        }
        return removed;
    }

    public Video extractAndSaveMetadata(String filePath) {
        return extractAndSaveMetadata(filePath, null);
    }

    public Video extractAndSaveMetadata(String filePath, Long libraryId) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = file.getAbsolutePath();
            String fileName = file.getName();

            Video existing = repository.findByFilePath(absolutePath).orElse(null);
            log.debug("extractAndSaveMetadata: filePath={}, libraryId={}, foundByPath={}",
                    absolutePath, libraryId, existing != null);

            if (existing == null) {
                existing = repository.findByFileName(fileName).orElse(null);
                if (existing != null) {
                    log.info("Updating moved video path: {} -> {}", existing.getFilePath(), absolutePath);
                    existing.setFilePath(absolutePath);
                    existing.setFileName(fileName);
                    existing.setFileSize(file.length());
                    existing.setFormat(TitleCleaner.getFileExtension(fileName).toUpperCase());
                    if (libraryId != null) {
                        existing.setLibraryId(libraryId);
                    }
                    Video updated = repository.save(existing);
                    log.debug("Updated video id={}, libraryId={}", updated.getId(), updated.getLibraryId());
                    return updated;
                }
            }

            Video video = existing != null ? existing : new Video();

            String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            // 清洗 title，去除集数信息，避免重复叠加
            String cleanTitle = cleanTitleForSearch(baseName);
            video.setTitle(cleanTitle.isBlank() ? baseName : cleanTitle);
            video.setFilePath(absolutePath);
            video.setFileName(fileName);
            video.setFileSize(file.length());
            video.setFormat(TitleCleaner.getFileExtension(fileName).toUpperCase());

            NfoService.NfoData nfoData = nfoService.readNfoForVideo(file.toPath());
            if (nfoData != null) {
                nfoService.applyNfoData(video, nfoData);
                log.debug("Applied local NFO metadata: {}", fileName);
            }

            // 从文件名解析集数，覆盖 NFO 中可能错误的值
            int[] se = parseSeasonEpisode(fileName);
            video.setSeasonNumber(se[0]);
            video.setEpisodeNumber(se[1]);

            if (libraryId != null && video.getLibraryId() == null) {
                video.setLibraryId(libraryId);
            }

            Video saved = repository.save(video);
            log.debug("Saved video id={}, title={}, libraryId={}, fileName={}",
                    saved.getId(), saved.getTitle(), saved.getLibraryId(), saved.getFileName());

            // 扫描阶段根据文件名/库过滤器推断 mediaType，不依赖 TMDB
            if (saved.getMediaType() == null) {
                String inferredMediaType = inferMediaType(saved, libraryId);
                if (inferredMediaType != null) {
                    saved.setMediaType(inferredMediaType);
                    repository.save(saved);
                }
            }

            // 用 ffprobe 分析技术元数据（同步执行，避免 SQLite 并发写锁）
            mediaInfoService.updateVideoMediaInfo(saved);

            // 扫描阶段立即整理文件到规范目录结构，刮削只负责更新元数据
            if (saved.getMediaType() != null) {
                moveVideoToMetadataDir(saved);
                renameVideoFile(saved);
            }

            return saved;
        } catch (Exception e) {
            log.error("Failed to extract metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to extract metadata: " + e.getMessage(), e);
        }
    }

    private void tryScrapeVideo(Video video) {
        try {
            String query = video.getTitle();
            if (query == null || query.isBlank()) {
                return;
            }

            String mediaTypeFilter = null;
            if (video.getLibraryId() != null) {
                MediaLibrary library = mediaLibraryService.getLibraryById(video.getLibraryId());
                mediaTypeFilter = library.getMediaTypeFilter();
                log.info("Scraping '{}' with library filter: libraryId={}, type={}, subType={}, filter={}",
                        video.getTitle(), video.getLibraryId(), library.getType(), library.getSubType(), mediaTypeFilter);
            } else {
                log.info("Scraping '{}' with no library filter (libraryId=null)", video.getTitle());
            }

            if (mediaTypeFilter == null && isTvEpisode(video)) {
                mediaTypeFilter = "tv";
                log.info("File '{}' parsed as TV episode (S{}E{}), forcing mediaType=tv",
                        video.getFileName(), video.getSeasonNumber(), video.getEpisodeNumber());
            }

            List<TmdbSearchResult.TmdbSearchItem> results = searchFromTmdb(query, mediaTypeFilter);
            if (results.isEmpty()) {
                log.info("No TMDB results for: {}", video.getTitle());
                markScrapeAttempted(video);
                return;
            }

            TmdbSearchResult.TmdbSearchItem bestMatch = pickBestTmdbMatch(results, query);
            if (bestMatch == null) {
                log.info("No confident TMDB match for: {}", video.getTitle());
                markScrapeAttempted(video);
                return;
            }

            scrapeAndBindTmdb(video.getId(), bestMatch.getId(), bestMatch.getMediaType());

            log.info("Auto-scraped: {} -> TMDB {} ({}) filter={}",
                    video.getTitle(), bestMatch.getId(), bestMatch.getMediaType(), mediaTypeFilter);
        } catch (Exception e) {
            log.warn("Failed to auto-scrape video {}: {}", video.getTitle(), e.getMessage(), e);
            markScrapeAttempted(video);
        }
    }

    private TmdbSearchResult.TmdbSearchItem pickBestTmdbMatch(
            List<TmdbSearchResult.TmdbSearchItem> results, String query) {
        String cleanedQuery = cleanTitleForSearch(query);

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

        // 2. 相似度匹配（同时检查 title 和 original_title）
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

    private void markScrapeAttempted(Video video) {
        try {
            video.setScrapeAttemptedAt(java.time.LocalDateTime.now());
            repository.save(video);
        } catch (Exception e) {
            log.debug("Failed to mark scrape attempt for video {}: {}", video.getId(), e.getMessage());
        }
    }

    private boolean isTvEpisode(Video video) {
        if (video.getSeasonNumber() != null && video.getEpisodeNumber() != null
                && (video.getSeasonNumber() > 1 || video.getEpisodeNumber() > 1)) {
            return true;
        }
        if (video.getFileName() != null) {
            String name = video.getFileName();
            if (SE_EP_PATTERN.matcher(name).find()) return true;
            if (SEASON_EPISODE_PATTERN.matcher(name).find()) return true;
            if (EP_PATTERN.matcher(name).find()) return true;
            if (HASH_PATTERN.matcher(name).find()) return true;
        }
        return false;
    }

    private String inferMediaType(Video video, Long libraryId) {
        // 优先使用库的媒体类型过滤器
        if (libraryId != null) {
            MediaLibrary library = mediaLibraryService.getLibraryById(libraryId);
            String filter = library.getMediaTypeFilter();
            if (filter != null && !filter.isBlank()) {
                return filter;
            }
        }
        // 根据文件名模式推断
        if (isTvEpisode(video)) {
            return "tv";
        }
        return null;
    }

    public void scanDirectory(String directoryPath) {
        scanDirectory(directoryPath, null);
    }

    public void scanDirectory(String directoryPath, Long libraryId) {
        log.debug("Scanning directory: {} (libraryId={})", directoryPath, libraryId);
        try {
            Path dir = Paths.get(directoryPath);
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Not a directory: " + directoryPath);
            }

            com.fryfrog.hub.common.util.DatabaseWriteLock.runInWriteLock(() -> {
                cleanupInvalidRecords();
                seriesService.cleanupDuplicateSeries();
            });

            long[] count = {0};
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            String extension = name.substring(name.lastIndexOf('.') + 1);
                            return SUPPORTED_FORMATS.contains(extension);
                        })
                        .forEach(path -> {
                            try {
                                count[0]++;
                                com.fryfrog.hub.common.util.DatabaseWriteLock.runInWriteLock(() ->
                                        extractAndSaveMetadata(path.toString(), libraryId));
                                log.debug("Indexed video: {} (libraryId={})", path.getFileName(), libraryId);
                            } catch (Exception e) {
                                log.warn("Failed to index video: {}", path.getFileName(), e);
                            }
                        });
            }
            log.debug("Scan complete: {} video files found in {}", count[0], directoryPath);

            com.fryfrog.hub.common.util.DatabaseWriteLock.runInWriteLock(this::autoGroupSeries);
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    private void autoGroupSeries() {
        com.fryfrog.hub.common.util.DatabaseWriteLock.lock();
        try {
        List<Video> allVideos = repository.findAll();

        Map<String, List<Video>> grouped = new LinkedHashMap<>();
        for (Video video : allVideos) {
            if (video.getSeries() != null) continue;

            String groupKey;
            if (video.getSeriesName() != null && !video.getSeriesName().isBlank()) {
                groupKey = video.getSeriesName();
            } else {
                groupKey = seriesService.cleanTitle(video.getTitle());
            }
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(video);
        }

        int groupedCount = 0;
        for (Map.Entry<String, List<Video>> entry : grouped.entrySet()) {
            List<Video> videos = entry.getValue();
            if (videos.size() < 2) continue;

            String seriesTitle = entry.getKey();
            VideoSeries series = seriesService.getOrCreateSeries(seriesTitle);

            for (Video video : videos) {
                int[] se = parseSeasonEpisode(video.getFileName());
                seriesService.assignVideoToSeries(video, series);
                if (video.getSeasonNumber() == null) video.setSeasonNumber(se[0]);
                if (video.getEpisodeNumber() == null) video.setEpisodeNumber(se[1]);
                repository.save(video);
            }

            series.setTotalEpisodes(videos.size());
            seriesService.saveSeries(series);
            groupedCount++;
            log.info("Auto-grouped series: {} ({} episodes)", seriesTitle, videos.size());
        }

        if (groupedCount > 0) {
            log.info("Auto-grouped {} series from scan", groupedCount);
        }
        } finally {
            com.fryfrog.hub.common.util.DatabaseWriteLock.unlock();
        }
    }

    private static final java.util.regex.Pattern SE_EP_PATTERN = java.util.regex.Pattern.compile("(?i)S(\\d{1,2})E(\\d{1,4})");
    private static final java.util.regex.Pattern SEASON_EPISODE_PATTERN = java.util.regex.Pattern.compile("(?i)Season\\s*(\\d{1,2})\\s*Episode\\s*(\\d{1,4})");
    private static final java.util.regex.Pattern EP_PATTERN = java.util.regex.Pattern.compile("(?i)EP?(\\d{1,4})");
    private static final java.util.regex.Pattern HASH_PATTERN = java.util.regex.Pattern.compile("[＃#](\\d{1,4})$");
    private static final java.util.regex.Pattern DASH_NUMBER_PATTERN = java.util.regex.Pattern.compile("[-–—]\\s*(\\d{1,4})\\b");
    private static final java.util.regex.Pattern TAIL_NUMBER_PATTERN = java.util.regex.Pattern.compile("^(.*?)[\\s._\\-　](\\d{1,4})$");
    private static final java.util.regex.Pattern CJK_TAIL_NUMBER_PATTERN = java.util.regex.Pattern.compile("^(.*?[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u309f\\u30a0-\\u30ff])(\\d{1,4})$");

    /** 匹配中文季目录名：第1季、第一季 */
    private static final java.util.regex.Pattern SEASON_DIR_PATTERN = java.util.regex.Pattern.compile(
            "第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*季"
    );

    public String cleanTitleForSearch(String title) {
        return com.fryfrog.hub.common.util.TitleCleaner.cleanForSearch(title);
    }

    /**
     * 从文件名解析季数和集数
     * 支持格式：
     * - S01E01.mp4 -> season=1, episode=1
     * - Season 1 Episode 1.mp4 -> season=1, episode=1
     * - EP01.mp4 -> season=1, episode=1
     * - 爱心符号多一点 1.mp4 -> season=1, episode=1（需要分隔符）
     * - パイハメ家族 ＃1.mp4 -> season=1, episode=1
     */
    public int[] parseSeasonEpisode(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return new int[]{1, 1};
        }

        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        var seMatch = SE_EP_PATTERN.matcher(baseName);
        if (seMatch.find()) {
            return new int[]{
                    Integer.parseInt(seMatch.group(1)),
                    Integer.parseInt(seMatch.group(2))
            };
        }

        var seasonEpisodeMatch = SEASON_EPISODE_PATTERN.matcher(baseName);
        if (seasonEpisodeMatch.find()) {
            return new int[]{
                    Integer.parseInt(seasonEpisodeMatch.group(1)),
                    Integer.parseInt(seasonEpisodeMatch.group(2))
            };
        }

        var epMatch = EP_PATTERN.matcher(baseName);
        if (epMatch.find()) {
            return new int[]{1, Integer.parseInt(epMatch.group(1))};
        }

        var hashMatch = HASH_PATTERN.matcher(baseName);
        if (hashMatch.find()) {
            return new int[]{1, Integer.parseInt(hashMatch.group(1))};
        }

        var dashNumberMatch = DASH_NUMBER_PATTERN.matcher(baseName);
        if (dashNumberMatch.find()) {
            return new int[]{1, Integer.parseInt(dashNumberMatch.group(1))};
        }

        var tailNumberMatch = TAIL_NUMBER_PATTERN.matcher(baseName);
        if (tailNumberMatch.find()) {
            int number = Integer.parseInt(tailNumberMatch.group(2));
            return new int[]{1, number};
        }

        var cjkTailNumberMatch = CJK_TAIL_NUMBER_PATTERN.matcher(baseName);
        if (cjkTailNumberMatch.find()) {
            int number = Integer.parseInt(cjkTailNumberMatch.group(2));
            return new int[]{1, number};
        }

        return new int[]{1, 1};
    }

    public List<TmdbSearchResult.TmdbSearchItem> searchFromTmdb(String query) {
        return searchFromTmdb(query, null);
    }

    public List<TmdbSearchResult.TmdbSearchItem> searchFromTmdb(String query, String mediaTypeFilter) {
        if (!tmdbService.isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cleanedQuery = cleanTitleForSearch(query);

        List<TmdbSearchResult.TmdbSearchItem> movieResults = List.of();
        List<TmdbSearchResult.TmdbSearchItem> tvResults = List.of();

        if (mediaTypeFilter == null || "movie".equalsIgnoreCase(mediaTypeFilter)) {
            movieResults = tmdbService.searchMovies(cleanedQuery);
        }
        if (mediaTypeFilter == null || "tv".equalsIgnoreCase(mediaTypeFilter)) {
            tvResults = tmdbService.searchTv(cleanedQuery);
        }

        if (movieResults.isEmpty() && tvResults.isEmpty() && !cleanedQuery.equals(query)) {
            log.info("No results for cleaned query '{}', trying original: '{}'", cleanedQuery, query);
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

    public Video scrapeAndBindTmdb(Long videoId, Long tmdbId, String mediaType) {
        return scrapeAndBindTmdb(videoId, tmdbId, mediaType, false);
    }

    public Video scrapeAndBindTmdb(Long videoId, Long tmdbId, String mediaType, boolean isAdult) {
        com.fryfrog.hub.common.util.DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doScrapeAndBind(videoId, tmdbId, mediaType, isAdult));
        } finally {
            com.fryfrog.hub.common.util.DatabaseWriteLock.unlock();
        }
    }

    @Transactional
    Video doScrapeAndBind(Long videoId, Long tmdbId, String mediaType, boolean isAdult) {
        Video video = getVideoById(videoId);
        Object detail = null;

        if ("movie".equalsIgnoreCase(mediaType)) {
            TmdbMovieDetail movieDetail = tmdbService.getMovieDetail(tmdbId);
            if (movieDetail == null) {
                throw new ResourceNotFoundException("TMDB Movie", "id", tmdbId);
            }
            updateVideoFromMovieDetail(video, movieDetail);
            detail = movieDetail;
        } else if ("tv".equalsIgnoreCase(mediaType)) {
            TmdbTvDetail tvDetail = tmdbService.getTvDetail(tmdbId);
            if (tvDetail == null) {
                throw new ResourceNotFoundException("TMDB TV", "id", tmdbId);
            }
            updateVideoFromTvDetail(video, tvDetail);
            detail = tvDetail;

            int[] seasonEpisode = parseSeasonEpisode(video.getFileName());
            video.setSeasonNumber(seasonEpisode[0]);
            video.setEpisodeNumber(seasonEpisode[1]);

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
                log.debug("Updated video with episode metadata: S{}E{} - {}",
                        seasonEpisode[0], seasonEpisode[1], episodeDetail.getName());
            } else {
                log.warn("Could not fetch episode metadata for S{}E{}, using show-level metadata",
                        seasonEpisode[0], seasonEpisode[1]);
            }

            VideoSeries series = seriesService.getOrCreateAndBindSeries(tvDetail.getName(), tmdbId);
            seriesService.assignVideoToSeries(video, series);

            Path metadataDir = nfoService.getMetadataDir(video);
            series.setMetadataDir(metadataDir.toString());
            seriesService.saveSeries(series);

            // 下载系列封面到季目录
            downloadSeriesCovers(series, metadataDir);
        } else {
            throw new IllegalArgumentException("Invalid media type: " + mediaType);
        }

        video.setTmdbId(tmdbId);
        video.setMediaType(mediaType);
        video.setMetadataSource("tmdb");
        video.setMetadataUpdatedAt(LocalDateTime.now());
        if (isAdult) {
            video.setIsAdult(true);
        }

        Video saved = repository.save(video);

        // 先重命名文件（使用新标题），再移动到新目录
        renameVideoFile(saved);
        moveVideoToMetadataDir(saved);
        generateNfoAndCovers(saved);
        saveActors(saved, mediaType, tmdbId, detail);

        return saved;
    }

    /**
     * 绑定 Hanime 元数据到视频
     */
    public Video scrapeAndBindHanime(Long videoId, String hanimeId) {
        com.fryfrog.hub.common.util.DatabaseWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doScrapeAndBindHanime(videoId, hanimeId));
        } finally {
            com.fryfrog.hub.common.util.DatabaseWriteLock.unlock();
        }
    }

    @Transactional
    Video doScrapeAndBindHanime(Long videoId, String hanimeId) {
        Video video = getVideoById(videoId);
        HanimeMetadata metadata = hanimeScraperService.scrape(hanimeId);

        if (metadata == null) {
            throw new ResourceNotFoundException("Hanime metadata", "hanimeId", hanimeId);
        }

        // 填充视频元数据
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
        video.setMetadataUpdatedAt(LocalDateTime.now());

        Video saved = repository.save(video);

        // 生成 NFO 和封面
        generateNfoAndCovers(saved);

        log.info("Bound Hanime metadata to video: {} -> hanimeId={}", saved.getTitle(), hanimeId);
        return saved;
    }

    /**
     * 仅刮削 Hanime 元数据（不绑定到视频）
     */
    public HanimeMetadata scrapeHanimeOnly(String hanimeId) {
        return hanimeScraperService.scrape(hanimeId);
    }

    private void moveVideoToMetadataDir(Video video) {
        try {
            Path metadataDir = nfoService.getMetadataDir(video);
            Files.createDirectories(metadataDir);

            Path videoPath = Paths.get(video.getFilePath());
            Path targetPath = metadataDir.resolve(video.getFileName());

            if (videoPath.equals(targetPath)) {
                return;
            }

            log.debug("Moving video: {} -> {}", videoPath, targetPath);

            if (!Files.exists(videoPath)) {
                log.warn("Source video not found: {}", videoPath);
                return;
            }

            // 移动视频文件（覆盖已存在的目标文件）
            Files.move(videoPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Successfully moved video: {} -> {}", videoPath, targetPath);

            // 移动外挂字幕
            String baseName = nfoService.getBaseName(video.getFileName());
            moveAssociatedSubtitles(videoPath.getParent(), targetPath.getParent(), baseName);

            video.setFilePath(targetPath.toString());
            repository.save(video);
        } catch (IOException e) {
            log.error("Failed to move video {} to metadata dir: {}", video.getFileName(), e.getMessage(), e);
        }
    }

    private void renameVideoFile(Video video) {
        try {
            Path videoPath = Paths.get(video.getFilePath());
            if (!Files.exists(videoPath)) {
                return;
            }

            String newFileName = generateCleanFileName(video);
            if (newFileName == null || newFileName.isBlank()) {
                return;
            }

            String oldExtension = TitleCleaner.getFileExtension(video.getFileName());
            String newExtension = TitleCleaner.getFileExtension(newFileName);
            if (newExtension.isBlank()) {
                newFileName = newFileName + "." + oldExtension;
            }

            if (video.getFileName().equals(newFileName)) {
                return;
            }

            Path newPath = videoPath.getParent().resolve(newFileName);

            String oldBaseName = nfoService.getBaseName(video.getFileName());
            // 覆盖已存在的目标文件
            Files.move(videoPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            Path parentDir = videoPath.getParent();
            renameAssociatedFile(parentDir, oldBaseName + ".nfo", newFileName);
            renamePosterFanartByPattern(parentDir, oldBaseName, newFileName);
            renameAssociatedSubtitles(parentDir, oldBaseName, newFileName);

            video.setFileName(newFileName);
            video.setFilePath(newPath.toString());
            repository.save(video);
            log.info("Renamed video: {} -> {}", video.getFileName(), newFileName);
        } catch (Exception e) {
            log.warn("Failed to rename video {}: {}", video.getFileName(), e.getMessage());
        }
    }

    private String generateCleanFileName(Video video) {
        String title = video.getTitle();
        if (title == null || title.isBlank()) {
            return null;
        }

        String extension = TitleCleaner.getFileExtension(video.getFileName());

        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            int season = video.getSeasonNumber() != null ? video.getSeasonNumber() : 1;
            int episode = video.getEpisodeNumber() != null ? video.getEpisodeNumber() : 1;
            return String.format("%s - S%02dE%02d.%s", title, season, episode, extension);
        } else {
            return title + "." + extension;
        }
    }

    private void renameAssociatedFile(Path dir, String oldBaseName, String newFileName) {
        try {
            String ext = TitleCleaner.getFileExtension(oldBaseName);
            String dottedExt = ext.isEmpty() ? "" : "." + ext;
            String newBaseName = nfoService.getBaseName(newFileName);
            Path oldFile = dir.resolve(oldBaseName);
            Path newFile = dir.resolve(newBaseName + dottedExt);
            if (Files.exists(oldFile) && !Files.exists(newFile)) {
                Files.move(oldFile, newFile);
                log.debug("Renamed associated file: {} -> {}", oldBaseName, newBaseName + dottedExt);
            }
        } catch (Exception e) {
            log.warn("Failed to rename associated file: {}", e.getMessage());
        }
    }

    private void renameAssociatedSubtitles(Path dir, String oldBaseName, String newFileName) {
        try {
            String newBaseName = nfoService.getBaseName(newFileName);
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(file -> {
                            String name = file.getFileName().toString();
                            return name.startsWith(oldBaseName) && SUBTITLE_EXTENSIONS.stream()
                                    .anyMatch(ext -> name.toLowerCase().endsWith(ext));
                        })
                        .forEach(file -> {
                            try {
                                String name = file.getFileName().toString();
                                String subExt = name.substring(name.lastIndexOf('.'));
                                Path newFile = dir.resolve(newBaseName + subExt);
                                if (!Files.exists(newFile)) {
                                    Files.move(file, newFile);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to rename subtitle: {}", e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("Failed to rename subtitles: {}", e.getMessage());
        }
    }

    private void generateNfoAndCovers(Video video) {
        try {
            nfoService.generateNfo(video);
            log.info("Generated NFO for: {}", video.getTitle());
        } catch (Exception e) {
            log.warn("Failed to generate NFO for {}: {}", video.getTitle(), e.getMessage());
        }

        // 电视剧同时生成 tvshow.nfo
        if ("tv".equalsIgnoreCase(video.getMediaType()) && video.getSeries() != null) {
            try {
                Path seasonDir = nfoService.getSeasonDir(video);
                nfoService.generateTvShowNfo(video.getSeries(), seasonDir);
                log.info("Generated tvshow.nfo for series: {}", video.getSeries().getTitle());
            } catch (Exception e) {
                log.warn("Failed to generate tvshow.nfo for {}: {}", video.getTitle(), e.getMessage());
            }
        }

        try {
            coverArtService.downloadAllCovers(video);
            log.debug("Downloaded covers for: {}", video.getTitle());
        } catch (Exception e) {
            log.warn("Failed to download covers for {}: {}", video.getTitle(), e.getMessage());
        }
    }

    /**
     * 下载系列封面到季目录（tvshow-poster.jpg, tvshow-fanart.jpg）
     */
    private void downloadSeriesCovers(VideoSeries series, Path episodeMetadataDir) {
        if (series.getPosterUrl() == null && series.getBackdropUrl() == null) return;

        // 季目录 = 集目录的父目录
        Path seasonDir = episodeMetadataDir.getParent();
        if (seasonDir == null) return;

        try {
            Files.createDirectories(seasonDir);
        } catch (IOException e) {
            log.warn("Failed to create season dir: {}", seasonDir);
            return;
        }

        boolean updated = false;

        // 下载系列海报
        if (series.getPosterUrl() != null) {
            Path posterPath = seasonDir.resolve("tvshow-poster.jpg");
            if (!Files.exists(posterPath)) {
                downloadCoverImage(series.getPosterUrl(), posterPath);
            }
            if (Files.exists(posterPath) && series.getPosterLocalPath() == null) {
                series.setPosterLocalPath(posterPath.toString());
                updated = true;
            }
        }

        // 下载系列背景图
        if (series.getBackdropUrl() != null) {
            Path fanartPath = seasonDir.resolve("tvshow-fanart.jpg");
            if (!Files.exists(fanartPath)) {
                downloadCoverImage(series.getBackdropUrl(), fanartPath);
            }
            if (Files.exists(fanartPath) && series.getBackdropLocalPath() == null) {
                series.setBackdropLocalPath(fanartPath.toString());
                updated = true;
            }
        }

        if (updated) {
            seriesService.saveSeries(series);
        }
    }

    private void downloadCoverImage(String imageUrl, Path targetPath) {
        try {
            String fullUrl = imageUrl.startsWith("http") ? imageUrl : "https://image.tmdb.org/t/p/original" + imageUrl;
            var resource = scraperRestTemplate.getForObject(fullUrl, org.springframework.core.io.Resource.class);
            if (resource != null) {
                try (var inputStream = resource.getInputStream()) {
                    Files.copy(inputStream, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                log.debug("Downloaded series cover: {}", targetPath);
            }
        } catch (Exception e) {
            log.debug("Failed to download series cover to {}: {}", targetPath, e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> organizeVideos(String path) {
        com.fryfrog.hub.common.util.DatabaseWriteLock.lock();
        try {
        List<Video> videos;
        if (path != null && !path.isBlank()) {
            videos = repository.findByFilePathContaining(path);
        } else {
            videos = repository.findAll();
        }

        int moved = 0;
        int skipped = 0;
        int failed = 0;

        for (Video video : videos) {
            try {
                Path oldDir = Paths.get(video.getFilePath()).getParent();
                Path metadataDir = nfoService.getMetadataDir(video);

                if (oldDir.equals(metadataDir)) {
                    skipped++;
                    continue;
                }

                Files.createDirectories(metadataDir);

                Path videoPath = Paths.get(video.getFilePath());
                Path newVideoPath = metadataDir.resolve(video.getFileName());

                // 移动视频文件
                if (Files.exists(videoPath) && !Files.exists(newVideoPath)) {
                    Files.move(videoPath, newVideoPath);
                    video.setFilePath(newVideoPath.toString());
                    log.debug("Moved video: {} -> {}", videoPath, newVideoPath);
                }

                // 移动关联的元数据文件（NFO、poster、fanart）
                String baseName = nfoService.getBaseName(video.getFileName());
                moveAssociatedFile(oldDir, metadataDir, baseName + ".nfo");
                moveAssociatedFile(oldDir, metadataDir, baseName + "-poster.jpg");
                moveAssociatedFile(oldDir, metadataDir, baseName + "-fanart.jpg");

                // 移动外挂字幕文件
                moveAssociatedSubtitles(oldDir, metadataDir, baseName);

                // 移动actors目录（电视剧在季目录下，电影在视频目录下）
                Path oldActorsDir = findOldActorsDir(oldDir);
                Path newActorsDir = metadataDir.resolve("actors");
                if (oldActorsDir != null && !Files.exists(newActorsDir)) {
                    Files.createDirectories(newActorsDir.getParent());
                    Files.move(oldActorsDir, newActorsDir);
                    log.debug("Moved actors dir: {} -> {}", oldActorsDir, newActorsDir);
                }

                repository.save(video);
                moved++;
            } catch (Exception e) {
                failed++;
                log.error("Failed to organize {}: {}", video.getFileName(), e.getMessage());
            }
        }

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("total", videos.size());
        result.put("moved", moved);
        result.put("skipped", skipped);
        result.put("failed", failed);
        return result;
        } finally {
            com.fryfrog.hub.common.util.DatabaseWriteLock.unlock();
        }
    }

    private void moveAssociatedFile(Path oldDir, Path newDir, String fileName) {
        try {
            Path oldFile = oldDir.resolve(fileName);
            Path newFile = newDir.resolve(fileName);
            if (Files.exists(oldFile) && !Files.exists(newFile)) {
                Files.move(oldFile, newFile);
                log.debug("Moved associated file: {}", fileName);
            }
        } catch (Exception e) {
            log.warn("Failed to move associated file {}: {}", fileName, e.getMessage());
        }
    }

    private void renamePosterFanartByPattern(Path dir, String oldBaseName, String newFileName) {
        try {
            String newBaseName = nfoService.getBaseName(newFileName);
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(file -> {
                            String name = file.getFileName().toString().toLowerCase();
                            return name.contains("-poster.jpg") || name.contains("-fanart.jpg");
                        })
                        .filter(file -> file.getFileName().toString().contains(oldBaseName))
                        .forEach(file -> {
                            try {
                                String name = file.getFileName().toString();
                                String suffix = name.contains("-poster") ? "-poster.jpg" : "-fanart.jpg";
                                Path newFile = dir.resolve(newBaseName + suffix);
                                if (!Files.exists(newFile)) {
                                    Files.move(file, newFile);
                                    log.debug("Renamed poster/fanart: {}", file.getFileName());
                                }
                            } catch (Exception e) {
                                log.warn("Failed to rename poster/fanart: {}", e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("Failed to list directory for poster/fanart: {}", e.getMessage());
        }
    }

    private static final java.util.Set<String> SUBTITLE_EXTENSIONS = java.util.Set.of(
            ".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx"
    );

    private void moveAssociatedSubtitles(Path oldDir, Path newDir, String baseName) {
        try (var stream = Files.list(oldDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString().toLowerCase();
                        String ext = name.substring(name.lastIndexOf('.'));
                        return SUBTITLE_EXTENSIONS.contains(ext);
                    })
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.startsWith(baseName);
                    })
                    .forEach(file -> {
                        try {
                            Path target = newDir.resolve(file.getFileName());
                            if (!Files.exists(target)) {
                                Files.move(file, target);
                                log.debug("Moved subtitle: {}", file.getFileName());
                            }
                        } catch (Exception e) {
                            log.warn("Failed to move subtitle {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to list directory for subtitles: {}", e.getMessage());
        }
    }

    private Path findOldActorsDir(Path videoDir) {
        // 先检查当前目录
        Path direct = videoDir.resolve("actors");
        if (Files.isDirectory(direct)) return direct;

        // 向上查找（电视剧actors可能在季目录下）
        Path current = videoDir.getParent();
        while (current != null) {
            if (current.getFileName() == null) break;
            Path actorsPath = current.resolve("actors");
            if (Files.isDirectory(actorsPath)) return actorsPath;
            current = current.getParent();
        }
        return null;
    }

    public void rescrapeAll() {
        rescrapeByLibrary(null);
    }

    public void rescrapeByLibrary(Long libraryId) {
        log.info("rescrapeByLibrary called with libraryId={}", libraryId);
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
                    log.warn("Failed to unbind video {}: {}", video.getTitle(), e.getMessage());
                }
            }
            cleanupNfoFiles(video);
        }
        if (bound > 0) {
            log.info("Unbound {} videos from library {}, starting re-scrape", bound, libraryId);
        }

        MediaLibrary library = mediaLibraryService.getLibraryById(libraryId);
        log.debug("About to scan directory: {} for libraryId={}", library.getPath(), libraryId);
        scanDirectory(library.getPath(), libraryId);
        log.debug("Finished scanning directory for libraryId={}", libraryId);

        autoScrapeAll(true);
    }

    private void cleanupNfoFiles(Video video) {
        try {
            Path videoPath = Paths.get(video.getFilePath());
            Path videoDir = videoPath.getParent();
            String baseName = nfoService.getBaseName(video.getFileName());

            Files.deleteIfExists(videoDir.resolve(baseName + ".nfo"));
            Files.deleteIfExists(videoDir.resolve(baseName + "-poster.jpg"));
            Files.deleteIfExists(videoDir.resolve(baseName + "-fanart.jpg"));

            Path metadataDir = nfoService.getMetadataDir(video);
            if (Files.isDirectory(metadataDir)) {
                Files.walk(metadataDir)
                    .filter(p -> p.getFileName().toString().startsWith(baseName))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
            }
            log.debug("Cleaned up NFO files for: {}", video.getFileName());
        } catch (Exception e) {
            log.warn("Failed to cleanup NFO files for {}: {}", video.getFileName(), e.getMessage());
        }
    }

    public List<Video> autoScrapeAll() {
        return autoScrapeAll(false);
    }

    /**
     * @param async true 时异步执行（从 API 调用），false 时同步执行（从 periodicScan 调用，在写锁内）
     */
    public List<Video> autoScrapeAll(boolean async) {
        if (!settingService.getBoolean("scrape.auto-scrape", true)) {
            log.debug("Auto-scrape is disabled by setting");
            return repository.findAll();
        }
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusDays(7);
        List<Video> videos = repository.findUnscrapedAfterCutoff(cutoff);
        if (videos.isEmpty()) {
            log.debug("No unbound videos to scrape");
            return repository.findAll();
        }

        Runnable scrapeTask = () -> {
            java.util.concurrent.atomic.AtomicInteger scraped = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger skipped = new java.util.concurrent.atomic.AtomicInteger(0);
            scrapeProgressService.start("video", videos.size());

            // 按系列分组，优先处理同一系列的内容
            List<List<Video>> seriesGroups = groupVideosBySeries(videos);
            log.info("Grouped {} videos into {} series groups (serial mode)", videos.size(), seriesGroups.size());

            // 完全串行处理：按系列顺序，每个视频依次处理
            for (List<Video> seriesGroup : seriesGroups) {
                if (seriesGroup.isEmpty()) continue;

                String seriesKey = getSeriesKey(seriesGroup.get(0));
                log.info("Processing series group '{}' ({} videos)", seriesKey, seriesGroup.size());

                // 同一系列内串行处理
                for (Video video : seriesGroup) {
                    try {
                        String query = video.getTitle();
                        if (query == null || query.isBlank()) {
                            scrapeProgressService.updateItem("video", video.getFileName(), "skipped", "empty title");
                            continue;
                        }

                        String mediaTypeFilter = null;
                        if (video.getLibraryId() != null) {
                            MediaLibrary library = mediaLibraryService.getLibraryById(video.getLibraryId());
                            mediaTypeFilter = library.getMediaTypeFilter();
                            log.info("Auto-scraping '{}' libraryId={} type={} subType={} filter={}",
                                    video.getTitle(), video.getLibraryId(), library.getType(), library.getSubType(), mediaTypeFilter);
                        } else {
                            log.info("Auto-scraping '{}' libraryId=null (no filter)", video.getTitle());
                        }

                        if (mediaTypeFilter == null && isTvEpisode(video)) {
                            mediaTypeFilter = "tv";
                            log.info("File '{}' parsed as TV episode (S{}E{}), forcing mediaType=tv",
                                    video.getFileName(), video.getSeasonNumber(), video.getEpisodeNumber());
                        }

                        scrapeProgressService.updateItem("video", video.getFileName(), "processing", null);

                        List<TmdbSearchResult.TmdbSearchItem> results = searchFromTmdb(query, mediaTypeFilter);
                        if (results.isEmpty()) {
                            log.info("No TMDB results for: {}", video.getTitle());
                            markScrapeAttempted(video);
                            scrapeProgressService.updateItem("video", video.getFileName(), "failed", "no TMDB results");
                            continue;
                        }

                        TmdbSearchResult.TmdbSearchItem bestMatch = pickBestTmdbMatch(results, query);
                        if (bestMatch == null) {
                            log.info("No confident TMDB match for: {}", video.getTitle());
                            markScrapeAttempted(video);
                            scrapeProgressService.updateItem("video", video.getFileName(), "failed", "no confident match");
                            continue;
                        }

                        boolean isAdult = Boolean.TRUE.equals(bestMatch.getAdult());
                        scrapeAndBindTmdb(video.getId(), bestMatch.getId(), bestMatch.getMediaType(), isAdult);
                        scraped.incrementAndGet();
                        scrapeProgressService.updateItem("video", video.getFileName(), "completed", null);

                        log.debug("Auto-scraped: {} -> TMDB {} ({}) (filter={})",
                                video.getTitle(), bestMatch.getId(), bestMatch.getMediaType(), mediaTypeFilter);
                    } catch (Exception e) {
                        log.warn("Failed to auto-scrape video {}: {}", video.getTitle(), e.getMessage());
                        markScrapeAttempted(video);
                        scrapeProgressService.updateItem("video", video.getFileName(), "failed", e.getMessage());
                    }
                }
                log.info("Completed series group '{}' ({} videos)", seriesKey, seriesGroup.size());
            }

            scrapeProgressService.finish("video");
            if (scraped.get() > 0) {
                log.info("Auto-scrape completed: {}/{} scraped, {} skipped", scraped.get(), videos.size(), skipped.get());
            }
        };

        if (async) {
            scrapeExecutor.submit(scrapeTask);
        } else {
            scrapeTask.run();
        }

        return repository.findAll();
    }

    /**
     * 按系列分组视频，优先处理同一系列的内容
     * 已绑定series的按series分组，未绑定的按标题相似度分组
     */
    private List<List<Video>> groupVideosBySeries(List<Video> videos) {
        // 使用LinkedHashMap保持插入顺序
        Map<String, List<Video>> grouped = new LinkedHashMap<>();

        for (Video video : videos) {
            String key = getSeriesKey(video);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(video);
        }

        // 按组大小降序排列，大的系列优先处理
        List<List<Video>> result = new ArrayList<>(grouped.values());
        result.sort((a, b) -> Integer.compare(b.size(), a.size()));

        return result;
    }

    /**
     * 获取视频的系列键，用于分组
     */
    private String getSeriesKey(Video video) {
        // 已绑定series的按series分组
        if (video.getSeries() != null) {
            return "series:" + video.getSeries().getId();
        }
        // 未绑定的按标题分组
        String title = video.getTitle();
        if (title == null || title.isBlank()) {
            return "unknown:" + video.getId();
        }
        return "title:" + cleanTitleForSearch(title);
    }

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

    private void saveActors(Video video, String mediaType, Long tmdbId, Object preloadedDetail) {
        try {
            actorRepository.deleteAll(actorRepository.findByVideo_Id(video.getId()));
            actorRepository.flush();

            Path actorsDir = getActorsDir(video, mediaType);
            if (actorsDir == null) return;
            Files.createDirectories(actorsDir);

            List<Object> members = new ArrayList<>();
            if ("movie".equalsIgnoreCase(mediaType)) {
                TmdbMovieDetail detail = preloadedDetail instanceof TmdbMovieDetail ? (TmdbMovieDetail) preloadedDetail : tmdbService.getMovieDetail(tmdbId);
                if (detail != null && detail.getCredits() != null && detail.getCredits().getCast() != null) {
                    for (TmdbMovieDetail.CastMember m : detail.getCredits().getCast()) {
                        members.add(m);
                        if (members.size() >= 10) break;
                    }
                }
            } else if ("tv".equalsIgnoreCase(mediaType)) {
                TmdbTvDetail detail = preloadedDetail instanceof TmdbTvDetail ? (TmdbTvDetail) preloadedDetail : tmdbService.getTvDetail(tmdbId);
                if (detail != null && detail.getCredits() != null && detail.getCredits().getCast() != null) {
                    for (TmdbTvDetail.CastMember m : detail.getCredits().getCast()) {
                        members.add(m);
                        if (members.size() >= 10) break;
                    }
                }
            }

            int count = 0;
            for (Object member : members) {
                try {
                    if (member instanceof TmdbMovieDetail.CastMember cm) {
                        count = saveOneActor(video, actorsDir, count, cm.getName(), cm.getCharacter(), cm.getId(), cm.getProfilePath());
                    } else if (member instanceof TmdbTvDetail.CastMember cm) {
                        count = saveOneActor(video, actorsDir, count, cm.getName(), cm.getCharacter(), cm.getId(), cm.getProfilePath());
                    }
                } catch (Exception e) {
                    log.warn("Failed to save actor for video id={}: {}", video.getId(), e.getMessage());
                }
            }
            log.debug("Saved {} actors for video id={}", count, video.getId());
        } catch (Exception e) {
            log.warn("Failed to save actors for video id={}: {}", video.getId(), e.getMessage());
        }
    }

    private Path getActorsDir(Video video, String mediaType) {
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        if (videoDir == null) return null;

        if ("tv".equalsIgnoreCase(mediaType)) {
            Path seasonDir = findSeasonDir(videoDir);
            if (seasonDir != null) {
                return seasonDir.resolve("actors");
            }
        }
        return videoDir.resolve("actors");
    }

    private Path findSeasonDir(Path episodeOrVideoDir) {
        Path current = episodeOrVideoDir;
        while (current != null) {
            if (current.getFileName() == null) break;
            String name = current.getFileName().toString();
            if (SEASON_DIR_PATTERN.matcher(name).matches()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private int saveOneActor(Video video, Path actorsDir, int count,
                              String name, String character, Long sourceId, String profilePath) throws IOException {
        if (name == null) return count;

        VideoActor actor = new VideoActor();
        actor.setVideo(video);
        actor.setName(name);
        actor.setCharacter(character);
        actor.setSourceActorId(sourceId);

        String imageUrl = null;
        if (profilePath != null && !profilePath.isBlank()) {
            imageUrl = "https://image.tmdb.org/t/p/w185" + profilePath;
            actor.setImageUrl(imageUrl);
        }

        String safeName = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (!safeName.isBlank() && imageUrl != null) {
            Path actorPath = actorsDir.resolve(safeName + ".jpg");
            if (!Files.exists(actorPath)) {
                try {
                    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                    headers.set("User-Agent", "FryfrogHub/0.1.0");
                    org.springframework.http.HttpEntity<Void> req = new org.springframework.http.HttpEntity<>(headers);
                    org.springframework.http.ResponseEntity<byte[]> resp = scraperRestTemplate.exchange(
                            imageUrl, org.springframework.http.HttpMethod.GET, req, byte[].class);
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        Files.write(actorPath, resp.getBody());
                    }
                } catch (Exception e) {
                    log.warn("Failed to download actor image '{}': {}", safeName, e.getMessage());
                }
            }
            actor.setImagePath(actorPath.toAbsolutePath().toString());
        }

        actorRepository.save(actor);
        return count + 1;
    }
}
