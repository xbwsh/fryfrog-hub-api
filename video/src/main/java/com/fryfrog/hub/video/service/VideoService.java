package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.video.dto.TmdbEpisodeDetail;
import com.fryfrog.hub.video.dto.TmdbMovieDetail;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

@Service
@Slf4j
public class VideoService {

    private final VideoRepository repository;
    private final TmdbService tmdbService;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final VideoWatcherService watcherService;
    private final SeriesService seriesService;
    private final TransactionTemplate transactionTemplate;

    private final ExecutorService scrapeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tmdb-scraper");
        t.setDaemon(true);
        return t;
    });

    public VideoService(VideoRepository repository, TmdbService tmdbService,
                       NfoService nfoService, CoverArtService coverArtService,
                       @Lazy VideoWatcherService watcherService, SeriesService seriesService,
                       TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.tmdbService = tmdbService;
        this.nfoService = nfoService;
        this.coverArtService = coverArtService;
        this.watcherService = watcherService;
        this.seriesService = seriesService;
        this.transactionTemplate = transactionTemplate;
    }

    @Value("${hub.video.root-path}")
    private String rootPath;

    @Value("${hub.tmdb.auto-scrape:false}")
    private boolean autoScrape;

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

    @Transactional
    public int cleanupInvalidRecords() {
        List<Video> allVideos = repository.findAll();
        int removed = 0;

        for (Video video : allVideos) {
            if (video.getFilePath() == null || !Files.exists(Paths.get(video.getFilePath()))) {
                log.info("Removing invalid record: {} (path: {})", video.getTitle(), video.getFilePath());
                if (video.getSeries() != null) {
                    seriesService.removeVideoFromSeries(video);
                }
                repository.deleteById(video.getId());
                removed++;
            }
        }

        log.info("Cleanup completed: removed {} invalid records", removed);
        return removed;
    }

    public Video extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = file.getAbsolutePath();
            String fileName = file.getName();

            Video existing = repository.findByFilePath(absolutePath).orElse(null);

            if (existing == null) {
                existing = repository.findByFileName(fileName).orElse(null);
                if (existing != null) {
                    log.info("Updating moved video path: {} -> {}", existing.getFilePath(), absolutePath);
                    existing.setFilePath(absolutePath);
                    existing.setFileName(fileName);
                    existing.setFileSize(file.length());
                    existing.setFormat(getFileExtension(fileName).toUpperCase());
                    return repository.save(existing);
                }
            }

            Video video = existing != null ? existing : new Video();

            String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            video.setTitle(baseName);
            video.setFilePath(absolutePath);
            video.setFileName(fileName);
            video.setFileSize(file.length());
            video.setFormat(getFileExtension(fileName).toUpperCase());

            Video saved = repository.save(video);

            if (autoScrape && saved.getTmdbId() == null) {
                scrapeExecutor.submit(() -> tryScrapeVideo(saved));
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

            List<TmdbSearchResult.TmdbSearchItem> results = searchFromTmdb(query);
            if (results.isEmpty()) {
                log.info("No TMDB results for: {}", video.getTitle());
                return;
            }

            TmdbSearchResult.TmdbSearchItem bestMatch = results.get(0);

            transactionTemplate.executeWithoutResult(status -> {
                scrapeAndBindTmdb(video.getId(), bestMatch.getId(), bestMatch.getMediaType());
            });

            log.info("Auto-scraped: {} -> TMDB {} ({})",
                    video.getTitle(), bestMatch.getId(), bestMatch.getMediaType());
        } catch (Exception e) {
            log.warn("Failed to auto-scrape video {}: {}", video.getTitle(), e.getMessage(), e);
        }
    }

    public void scanDirectory(String directoryPath) {
        try {
            Path dir = Paths.get(directoryPath);
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Not a directory: " + directoryPath);
            }

            cleanupInvalidRecords();
            seriesService.cleanupDuplicateSeries();

            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            String extension = name.substring(name.lastIndexOf('.') + 1);
                            return SUPPORTED_FORMATS.contains(extension);
                        })
                        .filter(path -> {
                            String absolutePath = path.toAbsolutePath().toString();
                            boolean exists = repository.findByFilePath(absolutePath).isPresent();
                            if (exists) {
                                log.debug("Skipping already indexed video: {}", path.getFileName());
                            }
                            return !exists;
                        })
                        .forEach(path -> {
                            try {
                                extractAndSaveMetadata(path.toString());
                                log.info("Indexed video: {}", path.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to index video: {}", path.getFileName(), e);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot + 1) : "";
    }

    public String cleanTitleForSearch(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }

        String cleaned = title;

        // 先处理明确的标记格式：S01E01, EP01, ＃1 等
        cleaned = cleaned.replaceAll("(?i)S\\d{1,2}E\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("(?i)EP?\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("[＃#]\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("[\\s._\\-]*(?:CD|DVD|BD|DISK?|PART|EP?|CHAPTER)[\\s._\\-#＃]*\\d{1,4}[\\s._\\-]*$", "");

        // 处理末尾数字（有分隔符或无分隔符）
        cleaned = cleaned.replaceAll("[\\s._\\-＃#EPep]+\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("\\d{1,4}$", "");

        cleaned = cleaned.replaceAll("[\\[\\]【】()（）]", " ");

        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        log.debug("Cleaned title: '{}' -> '{}'", title, cleaned);

        return cleaned.isBlank() ? title : cleaned;
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

        // 尝试匹配 S01E01 格式
        var seMatch = java.util.regex.Pattern.compile("(?i)S(\\d{1,2})E(\\d{1,4})").matcher(baseName);
        if (seMatch.find()) {
            return new int[]{
                    Integer.parseInt(seMatch.group(1)),
                    Integer.parseInt(seMatch.group(2))
            };
        }

        // 尝试匹配 Season X Episode Y 格式
        var seasonEpisodeMatch = java.util.regex.Pattern.compile("(?i)Season\\s*(\\d{1,2})\\s*Episode\\s*(\\d{1,4})").matcher(baseName);
        if (seasonEpisodeMatch.find()) {
            return new int[]{
                    Integer.parseInt(seasonEpisodeMatch.group(1)),
                    Integer.parseInt(seasonEpisodeMatch.group(2))
            };
        }

        // 尝试匹配 EP01 或 EP1 格式
        var epMatch = java.util.regex.Pattern.compile("(?i)EP?(\\d{1,4})").matcher(baseName);
        if (epMatch.find()) {
            return new int[]{1, Integer.parseInt(epMatch.group(1))};
        }

        // 尝试匹配 ＃数字 或 #数字 格式（全角/半角井号）
        var hashMatch = java.util.regex.Pattern.compile("[＃#](\\d{1,4})$").matcher(baseName);
        if (hashMatch.find()) {
            return new int[]{1, Integer.parseInt(hashMatch.group(1))};
        }

        // 尝试匹配末尾数字，但要求前面有明确的分隔符
        // 分隔符：空格、点、下划线、减号、全角空格等
        var tailNumberMatch = java.util.regex.Pattern.compile("^(.*?)[\\s._\\-　](\\d{1,4})$").matcher(baseName);
        if (tailNumberMatch.find()) {
            int number = Integer.parseInt(tailNumberMatch.group(2));
            return new int[]{1, number};
        }

        // 中文/日文标题+数字格式（如：爱心符号多一点1、パイハメ家族1）
        var cjkTailNumberMatch = java.util.regex.Pattern.compile("^(.*?[\\u4e00-\\u9fa5\\u3040-\\u309f\\u30a0-\\u30ff])(\\d{1,4})$").matcher(baseName);
        if (cjkTailNumberMatch.find()) {
            int number = Integer.parseInt(cjkTailNumberMatch.group(2));
            return new int[]{1, number};
        }

        return new int[]{1, 1};
    }

    public List<TmdbSearchResult.TmdbSearchItem> searchFromTmdb(String query) {
        if (!tmdbService.isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cleanedQuery = cleanTitleForSearch(query);

        List<TmdbSearchResult.TmdbSearchItem> movieResults = tmdbService.searchMovies(cleanedQuery);
        List<TmdbSearchResult.TmdbSearchItem> tvResults = tmdbService.searchTv(cleanedQuery);

        if (movieResults.isEmpty() && tvResults.isEmpty() && !cleanedQuery.equals(query)) {
            log.info("No results for cleaned query '{}', trying original: '{}'", cleanedQuery, query);
            movieResults = tmdbService.searchMovies(query);
            tvResults = tmdbService.searchTv(query);
        }

        movieResults.addAll(tvResults);
        movieResults.sort((a, b) -> {
            double scoreA = a.getVoteAverage() != null ? a.getVoteAverage() : 0;
            double scoreB = b.getVoteAverage() != null ? b.getVoteAverage() : 0;
            return Double.compare(scoreB, scoreA);
        });

        return movieResults;
    }

    @Transactional
    public Video scrapeAndBindTmdb(Long videoId, Long tmdbId, String mediaType) {
        Video video = getVideoById(videoId);

        if ("movie".equalsIgnoreCase(mediaType)) {
            TmdbMovieDetail detail = tmdbService.getMovieDetail(tmdbId);
            if (detail == null) {
                throw new ResourceNotFoundException("TMDB Movie", "id", tmdbId);
            }
            updateVideoFromMovieDetail(video, detail);
        } else if ("tv".equalsIgnoreCase(mediaType)) {
            TmdbTvDetail detail = tmdbService.getTvDetail(tmdbId);
            if (detail == null) {
                throw new ResourceNotFoundException("TMDB TV", "id", tmdbId);
            }
            updateVideoFromTvDetail(video, detail);

            // 解析文件名中的季数和集数
            int[] seasonEpisode = parseSeasonEpisode(video.getFileName());
            video.setSeasonNumber(seasonEpisode[0]);
            video.setEpisodeNumber(seasonEpisode[1]);

            // 获取集级元数据
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
                log.info("Updated video with episode metadata: S{}E{} - {}", 
                        seasonEpisode[0], seasonEpisode[1], episodeDetail.getName());
            } else {
                log.warn("Could not fetch episode metadata for S{}E{}, using show-level metadata", 
                        seasonEpisode[0], seasonEpisode[1]);
            }

            VideoSeries series = seriesService.getOrCreateAndBindSeries(detail.getName(), tmdbId);
            seriesService.assignVideoToSeries(video, series);

            Path metadataDir = nfoService.getMetadataDir(video);
            series.setMetadataDir(metadataDir.toString());
            seriesService.saveSeries(series);
        } else {
            throw new IllegalArgumentException("Invalid media type: " + mediaType);
        }

        video.setTmdbId(tmdbId);
        video.setMediaType(mediaType);
        video.setMetadataSource("tmdb");
        video.setMetadataUpdatedAt(LocalDateTime.now());

        Video saved = repository.save(video);

        generateNfoAndCovers(saved);
        moveVideoToMetadataDir(saved);

        return saved;
    }

    private void moveVideoToMetadataDir(Video video) {
        try {
            Path metadataDir = nfoService.getMetadataDir(video);
            Files.createDirectories(metadataDir);

            Path videoPath = Paths.get(video.getFilePath());
            Path targetPath = metadataDir.resolve(video.getFileName());

            log.info("Moving video: {} -> {}", videoPath, targetPath);

            if (videoPath.equals(targetPath)) {
                log.debug("Video already in metadata dir: {}", videoPath);
                return;
            }

            if (!Files.exists(videoPath)) {
                log.warn("Source video not found: {}", videoPath);
                return;
            }

            if (Files.exists(targetPath)) {
                log.info("Target video already exists, updating path: {}", targetPath);
                video.setFilePath(targetPath.toString());
                repository.save(video);
                return;
            }

            watcherService.addScrapingPath(videoPath.toString());
            watcherService.addScrapingPath(targetPath.toString());
            try {
                Files.move(videoPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Successfully moved video: {} -> {}", videoPath, targetPath);
            } finally {
                watcherService.removeScrapingPath(videoPath.toString());
                watcherService.removeScrapingPath(targetPath.toString());
            }

            video.setFilePath(targetPath.toString());
            repository.save(video);
        } catch (IOException e) {
            log.error("Failed to move video {} to metadata dir: {}", video.getFileName(), e.getMessage(), e);
        }
    }

    private void generateNfoAndCovers(Video video) {
        try {
            nfoService.generateNfo(video);
            log.info("Generated NFO for: {}", video.getTitle());
        } catch (Exception e) {
            log.warn("Failed to generate NFO for {}: {}", video.getTitle(), e.getMessage());
        }

        try {
            coverArtService.downloadAllCovers(video);
            log.info("Downloaded covers for: {}", video.getTitle());
        } catch (Exception e) {
            log.warn("Failed to download covers for {}: {}", video.getTitle(), e.getMessage());
        }
    }

    public List<Video> autoScrapeAll() {
        List<Video> videos = repository.findByTmdbIdIsNull();
        int scraped = 0;

        for (Video video : videos) {
            try {
                String query = video.getTitle();
                if (query == null || query.isBlank()) {
                    continue;
                }

                List<TmdbSearchResult.TmdbSearchItem> results = searchFromTmdb(query);
                if (results.isEmpty()) {
                    log.info("No TMDB results for: {}", video.getTitle());
                    continue;
                }

                TmdbSearchResult.TmdbSearchItem bestMatch = results.get(0);
                scrapeAndBindTmdb(video.getId(), bestMatch.getId(), bestMatch.getMediaType());
                scraped++;

                log.info("Auto-scraped: {} -> TMDB {} ({})",
                        video.getTitle(), bestMatch.getId(), bestMatch.getMediaType());
            } catch (Exception e) {
                log.warn("Failed to auto-scrape video {}: {}", video.getTitle(), e.getMessage());
            }
        }

        log.info("Auto-scrape completed: {}/{} videos scraped", scraped, videos.size());
        return repository.findAll();
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
        video.setGenre(detail.getGenres());
        video.setRating(detail.getVoteAverage());
        video.setVoteCount(detail.getVoteCount());
        video.setPosterUrl(tmdbService.getPosterUrl(detail.getPosterPath()));
        video.setBackdropUrl(tmdbService.getBackdropUrl(detail.getBackdropPath()));
    }
}
