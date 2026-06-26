package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.video.dto.TmdbEpisodeDetail;
import com.fryfrog.hub.video.dto.TmdbMovieDetail;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
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
public class VideoService {

    private final VideoRepository repository;
    private final TmdbService tmdbService;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final VideoWatcherService watcherService;
    private final SeriesService seriesService;
    private final TransactionTemplate transactionTemplate;
    private final SystemSettingService settingService;
    private final VideoActorRepository actorRepository;
    private final RestTemplate scraperRestTemplate;
    private final ScrapeProgressService scrapeProgressService;

    private final ExecutorService scrapeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tmdb-scraper");
        t.setDaemon(true);
        return t;
    });

    public VideoService(VideoRepository repository, TmdbService tmdbService,
                       NfoService nfoService, CoverArtService coverArtService,
                       @Lazy VideoWatcherService watcherService, SeriesService seriesService,
                       TransactionTemplate transactionTemplate, SystemSettingService settingService,
                       VideoActorRepository actorRepository,
                       @org.springframework.beans.factory.annotation.Qualifier("scraperRestTemplate") RestTemplate scraperRestTemplate,
                       ScrapeProgressService scrapeProgressService) {
        this.repository = repository;
        this.tmdbService = tmdbService;
        this.nfoService = nfoService;
        this.coverArtService = coverArtService;
        this.watcherService = watcherService;
        this.seriesService = seriesService;
        this.transactionTemplate = transactionTemplate;
        this.settingService = settingService;
        this.actorRepository = actorRepository;
        this.scraperRestTemplate = scraperRestTemplate;
        this.scrapeProgressService = scrapeProgressService;
    }

    @Value("${hub.video.root-paths:./media-library/video}")
    private String rootPathsConfig;

    public List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public String getFirstRootPath() {
        List<String> paths = getRootPaths();
        return paths.isEmpty() ? "./media-library/video" : paths.get(0);
    }

    private boolean isAutoScrape() {
        return settingService.getBoolean("hub.tmdb.auto-scrape", false);
    }

    private double getMinScore() {
        return settingService.getDouble("hub.tmdb.min-score", 0.0);
    }

    private static final Set<String> SUPPORTED_FORMATS = Set.of("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v");

    public String getRootPath() {
        return getFirstRootPath();
    }

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
    public Video unbindTmdb(Long videoId) {
        Video video = getVideoById(videoId);

        if (video.getTmdbId() == null) {
            throw new IllegalStateException("Video is not bound to TMDB");
        }

        log.info("Unbinding TMDB from video: {} (tmdbId={})", video.getTitle(), video.getTmdbId());

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

    @Transactional
    public Video rescrapeVideo(Long videoId, Long tmdbId, String mediaType) {
        unbindTmdb(videoId);
        return scrapeAndBindTmdb(videoId, tmdbId, mediaType);
    }

    public Video rescrapeVideo(Long videoId) {
        Video video = getVideoById(videoId);
        if (video.getTmdbId() == null || video.getMediaType() == null) {
            throw new IllegalArgumentException("Video has no TMDB binding: " + videoId);
        }
        Long tmdbId = video.getTmdbId();
        String mediaType = video.getMediaType();
        unbindTmdb(videoId);
        return scrapeAndBindTmdb(videoId, tmdbId, mediaType);
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

        seriesService.cleanupEmptySeries();

        if (removed > 0) {
            log.info("Cleanup completed: removed {} invalid records", removed);
        }
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
                    Video updated = repository.save(existing);
                    if (isAutoScrape() && updated.getTmdbId() == null) {
                        scrapeExecutor.submit(() -> tryScrapeVideo(updated));
                    }
                    return updated;
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

            NfoService.NfoData nfoData = nfoService.readNfoForVideo(file.toPath());
            if (nfoData != null) {
                nfoService.applyNfoData(video, nfoData);
                log.debug("Applied local NFO metadata: {}", fileName);
            }

            // 从文件名解析集数，覆盖 NFO 中可能错误的值
            int[] se = parseSeasonEpisode(fileName);
            video.setSeasonNumber(se[0]);
            video.setEpisodeNumber(se[1]);

            Video saved = repository.save(video);

            if (isAutoScrape() && saved.getTmdbId() == null && nfoData == null) {
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
            double score = bestMatch.getVoteAverage() != null ? bestMatch.getVoteAverage() : 0.0;

            if (score < getMinScore()) {
                log.debug("Skipping auto-scrape for {} - score {} below threshold {}", video.getTitle(), score, getMinScore());
                return;
            }

            transactionTemplate.executeWithoutResult(status -> {
                scrapeAndBindTmdb(video.getId(), bestMatch.getId(), bestMatch.getMediaType());
            });

            log.debug("Auto-scraped: {} -> TMDB {} ({}) score={}",
                    video.getTitle(), bestMatch.getId(), bestMatch.getMediaType(), score);
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
                        .forEach(path -> {
                            try {
                                extractAndSaveMetadata(path.toString());
                                log.debug("Indexed video: {}", path.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to index video: {}", path.getFileName(), e);
                            }
                        });
            }

            autoGroupSeries();
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    private void autoGroupSeries() {
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
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot + 1) : "";
    }

    private static final String DOTTED_QUALITY =
            "(?i)\\bH\\.?264\\b|\\bH\\.?265\\b|\\bDD[P+]\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b" +
            "|\\bDTS(?:\\s*-?\\s*HD)?(?:\\s*\\.?\\s*(?:MA|ES|RA))?(?:\\s*\\.?\\s*\\d+(?:\\.\\d+)*)?\\b" +
            "|\\bAC\\s*\\.?\\s*3\\b|\\bE\\s*-?\\s*AC\\s*-?\\s*3\\b" +
            "|\\bA(?:V|C)\\s*\\.?\\s*A(?:V|C)?\\s*\\.?\\s*1\\b" +
            "|\\bMPEG\\s*-?\\s*[24]\\b" +
            "|\\bFLAC\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b";

    private static final String QUALITY_PATTERN =
            "(?i)\\b(?:2160p|1080p|720p|480p|4K|UHD|FHD)\\b" +
            "|\\b(?:BluRay|BDRip|BRRip|WEB-?DL|WEB-?Rip|HDRip|DVDRip|HDTV|TVRip|CamRip|TS|TC|SCR|R5)\\b" +
            "|\\b(?:x264|x265|HEVC|AVC|AV1)\\b" +
            "|\\b(?:AAC|FLAC|DTS(?:MA|HD)?|AC3|EAC3|DDP?\\d|Atmos|TrueHD|MP3|OGG|Opus)\\b" +
            "|\\bHDR(?:10?)?\\b|\\bDoVi\\b|\\bDV\\b|\\bHLG\\b" +
            "|\\b(?:10-?bit|8-?bit)\\b" +
            "|\\bREMUX\\b|\\bBlu-?ray\\b" +
            "|\\b(?:AVS|FRDS|Ma10p|Ma10s|NCOP|NCED)\\b" +
            "|\\b(?:Baha|ADBA|Bilibili|ABEMA|Crunchyroll|Funimation|Netflix|Disney\\+?|Amazon|Hulu|Hi10|Nazzy|FGT|SPARKS|SHAFT)\\b" +
            "|\\b(?:ASS|SSA|SRT|BIG5|GB2312|UTF-?8|EUC-?JP|Shift-?JIS)\\b" +
            "|\\b(?:PART|CHAPTER|CD|DVD|BD|DISC?)\\b";

    public String cleanTitleForSearch(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }

        String cleaned = title;

        cleaned = cleaned.replaceAll("\\[.*?\\]", " ");
        cleaned = cleaned.replaceAll("【.*?】", " ");
        cleaned = cleaned.replaceAll("\\(.*?\\)", " ");
        cleaned = cleaned.replaceAll("（.*?）", " ");

        cleaned = cleaned.replaceAll(DOTTED_QUALITY, " ");
        cleaned = cleaned.replaceAll("[._]", " ");

        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\s*E\\d{1,4}\\b", " ");
        cleaned = cleaned.replaceAll(QUALITY_PATTERN, " ");

        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:季|部|期)", " ");
        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:话|集|回|篇|章)", " ");

        cleaned = cleaned.replaceAll("(?i)\\bSeason\\s*\\d+\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\b", " ");

        cleaned = cleaned.replaceAll("(?i)\\bE(?:p(?:isode)?)?\\s*\\d{1,4}\\b", " ");
        cleaned = cleaned.replaceAll("[＃#]\\s*\\d{1,4}\\b", " ");

        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("\\s+\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("^\\d{1,4}\\s*[-–—]\\s*", "");
        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*$", "");

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

        // 尝试匹配 - 02 或 – 02 格式（减号后跟数字，数字后可以有其他内容如标签）
        var dashNumberMatch = java.util.regex.Pattern.compile("[-–—]\\s*(\\d{1,4})\\b").matcher(baseName);
        if (dashNumberMatch.find()) {
            return new int[]{1, Integer.parseInt(dashNumberMatch.group(1))};
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
        saveActors(saved, mediaType, tmdbId);

        if ("tv".equalsIgnoreCase(mediaType)) {
            bindSiblingVideos(saved, tmdbId);
        }

        return saved;
    }

    private void bindSiblingVideos(Video boundVideo, Long tmdbId) {
        String dirPath = Paths.get(boundVideo.getFilePath()).getParent().toString();
        String dirPattern = dirPath + "/%";
        List<Video> siblings = repository.findUnboundInDirectory(dirPattern);
        for (Video sibling : siblings) {
            if (sibling.getId().equals(boundVideo.getId())) {
                continue;
            }
            try {
                int[] seasonEpisode = parseSeasonEpisode(sibling.getFileName());
                sibling.setTmdbId(tmdbId);
                sibling.setMediaType("tv");
                sibling.setMetadataSource("tmdb");
                sibling.setSeasonNumber(seasonEpisode[0]);
                sibling.setEpisodeNumber(seasonEpisode[1]);
                sibling.setMetadataUpdatedAt(LocalDateTime.now());

                VideoSeries series = seriesService.getOrCreateAndBindSeries(boundVideo.getTitle(), tmdbId);
                seriesService.assignVideoToSeries(sibling, series);

                repository.save(sibling);
                generateNfoAndCovers(sibling);
                saveActors(sibling, "tv", tmdbId);
                log.info("Auto-bound sibling video: {} -> TMDB {}", sibling.getFileName(), tmdbId);
            } catch (Exception e) {
                log.warn("Failed to auto-bind sibling video {}: {}", sibling.getFileName(), e.getMessage());
            }
        }
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
                log.debug("Target video already exists, updating path: {}", targetPath);
                video.setFilePath(targetPath.toString());
                repository.save(video);
                return;
            }

            watcherService.addScrapingPath(videoPath.toString());
            watcherService.addScrapingPath(targetPath.toString());
            try {
                Files.move(videoPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Successfully moved video: {} -> {}", videoPath, targetPath);
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

    @Transactional
    public Map<String, Object> organizeVideos(String path) {
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

    public List<Video> autoScrapeAll() {
        List<Video> videos = repository.findByTmdbIdIsNull();
        int scraped = 0;
        int skipped = 0;

        scrapeProgressService.start("video", videos.size());

        for (Video video : videos) {
            try {
                String query = video.getTitle();
                if (query == null || query.isBlank()) {
                    scrapeProgressService.updateItem("video", video.getFileName(), "skipped", "empty title");
                    continue;
                }

                scrapeProgressService.updateItem("video", video.getFileName(), "processing", null);

                List<TmdbSearchResult.TmdbSearchItem> results = searchFromTmdb(query);
                if (results.isEmpty()) {
                    log.info("No TMDB results for: {}", video.getTitle());
                    scrapeProgressService.updateItem("video", video.getFileName(), "failed", "no TMDB results");
                    continue;
                }

                TmdbSearchResult.TmdbSearchItem bestMatch = results.get(0);
                double score = bestMatch.getVoteAverage() != null ? bestMatch.getVoteAverage() : 0.0;

                if (score < getMinScore()) {
                    log.debug("Skipping {} - score {} below threshold {}", video.getTitle(), score, getMinScore());
                    scrapeProgressService.updateItem("video", video.getFileName(), "skipped", "score below threshold");
                    skipped++;
                    continue;
                }

                scrapeAndBindTmdb(video.getId(), bestMatch.getId(), bestMatch.getMediaType());
                scraped++;
                scrapeProgressService.updateItem("video", video.getFileName(), "completed", null);

                log.debug("Auto-scraped: {} -> TMDB {} ({}) score={}",
                        video.getTitle(), bestMatch.getId(), bestMatch.getMediaType(), score);
            } catch (Exception e) {
                log.warn("Failed to auto-scrape video {}: {}", video.getTitle(), e.getMessage());
                scrapeProgressService.updateItem("video", video.getFileName(), "failed", e.getMessage());
            }
        }

        scrapeProgressService.finish("video");
        if (scraped > 0) {
            log.info("Auto-scrape completed: {}/{} scraped, {} skipped (threshold={})", scraped, videos.size(), skipped, getMinScore());
        }
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
        video.setActors(detail.getActors());
        video.setGenre(detail.getGenres());
        video.setRating(detail.getVoteAverage());
        video.setVoteCount(detail.getVoteCount());
        video.setStatus(detail.getStatus());
        video.setPosterUrl(tmdbService.getPosterUrl(detail.getPosterPath()));
        video.setBackdropUrl(tmdbService.getBackdropUrl(detail.getBackdropPath()));
    }

    private void saveActors(Video video, String mediaType, Long tmdbId) {
        try {
            actorRepository.deleteAll(actorRepository.findByVideoId(video.getId()));

            Path actorsDir = getActorsDir(video, mediaType);
            if (actorsDir == null) return;
            Files.createDirectories(actorsDir);

            int count = 0;
            if ("movie".equalsIgnoreCase(mediaType)) {
                TmdbMovieDetail detail = tmdbService.getMovieDetail(tmdbId);
                if (detail != null && detail.getCredits() != null && detail.getCredits().getCast() != null) {
                    for (TmdbMovieDetail.CastMember member : detail.getCredits().getCast()) {
                        if (count >= 10) break;
                        count = saveOneActor(video, actorsDir, count,
                                member.getName(), member.getCharacter(), member.getId(), member.getProfilePath());
                    }
                }
            } else if ("tv".equalsIgnoreCase(mediaType)) {
                TmdbTvDetail detail = tmdbService.getTvDetail(tmdbId);
                if (detail != null && detail.getCredits() != null && detail.getCredits().getCast() != null) {
                    for (TmdbTvDetail.CastMember member : detail.getCredits().getCast()) {
                        if (count >= 10) break;
                        count = saveOneActor(video, actorsDir, count,
                                member.getName(), member.getCharacter(), member.getId(), member.getProfilePath());
                    }
                }
            }
            log.info("Saved {} actors for video id={}", count, video.getId());
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
            if (name.matches("第 \\d+ 季")) {
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
        actor.setVideoId(video.getId());
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
