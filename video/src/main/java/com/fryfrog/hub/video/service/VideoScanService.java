package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.util.DatabaseWriteLock;
import com.fryfrog.hub.common.util.TitleCleaner;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 视频扫描服务：负责发现视频文件、提取基础元数据、批量入库。
 * 所有 I/O 操作（文件扫描、ffprobe）在写锁外执行，只在 DB 批量写入时短暂持有写锁。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoScanService {

    private final VideoRepository repository;
    private final MediaInfoService mediaInfoService;
    private final NfoService nfoService;
    private final MediaLibraryService mediaLibraryService;

    static final Set<String> SUPPORTED_FORMATS = Set.of("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v");

    // ==================== 集数解析正则 ====================

    private static final Pattern SE_EP_PATTERN = Pattern.compile("(?i)S(\\d{1,2})E(\\d{1,4})");
    private static final Pattern SEASON_EPISODE_PATTERN = Pattern.compile("(?i)Season\\s*(\\d{1,2})\\s*Episode\\s*(\\d{1,4})");
    private static final Pattern EP_PATTERN = Pattern.compile("(?i)EP?(\\d{1,4})");
    private static final Pattern HASH_PATTERN = Pattern.compile("[＃#](\\d{1,4})$");
    private static final Pattern DASH_NUMBER_PATTERN = Pattern.compile("[-–—]\\s*(\\d{1,4})\\b");
    private static final Pattern TAIL_NUMBER_PATTERN = Pattern.compile("^(.*?)[\\s._\\-　](\\d{1,4})$");
    private static final Pattern CJK_TAIL_NUMBER_PATTERN = Pattern.compile("^(.*?[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u309f\\u30a0-\\u30ff])(\\d{1,4})$");

    /**
     * 扫描目录并批量入库（完整流程：扫描 → 提取元数据 → ffprobe → DB 写入）
     */
    public List<Video> scanAndSave(String directoryPath, Long libraryId) {
        log.debug("[Scan] Start scanning directory: {} (libraryId={})", directoryPath, libraryId);
        long startTime = System.currentTimeMillis();

        // Phase 1: 清理无效记录（需要写锁）
        DatabaseWriteLock.runInWriteLock(() -> {
            cleanupInvalidRecords();
            cleanupDuplicateSeries();
        });

        // Phase 2: 扫描文件系统（无锁）
        List<Path> videoFiles = collectVideoFiles(directoryPath);
        if (videoFiles.isEmpty()) {
            log.debug("[Scan] No video files found in: {}", directoryPath);
            return Collections.emptyList();
        }
        log.debug("[Scan] Found {} video files", videoFiles.size());

        // Phase 3: 批量提取元数据（无锁，内存操作）
        List<Video> videos = new ArrayList<>();
        for (int i = 0; i < videoFiles.size(); i++) {
            Path path = videoFiles.get(i);
            try {
                if ((i + 1) % 10 == 0 || i == 0) {
                    log.debug("[Scan] Processing file {}/{}: {}", i + 1, videoFiles.size(), path.getFileName());
                }
                Video video = extractMetadata(path, libraryId);
                videos.add(video);
            } catch (Exception e) {
                log.warn("[Scan] Failed to extract metadata from {}: {}", path.getFileName(), e.getMessage());
            }
        }

        // Phase 4: 批量入库（单次写锁）
        DatabaseWriteLock.runInWriteLock(() -> {
            repository.saveAll(videos);
        });
        log.debug("[Scan] Saved {} videos to database", videos.size());

        // Phase 5: ffprobe 分析技术元数据（无锁，可并发）
        for (Video video : videos) {
            try {
                mediaInfoService.updateVideoMediaInfo(video);
            } catch (Exception e) {
                log.debug("[Scan] Failed to analyze media info for {}: {}", video.getFileName(), e.getMessage());
            }
        }

        // Phase 6: 自动分组系列（需要写锁）
        DatabaseWriteLock.runInWriteLock(this::autoGroupSeries);

        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("[Scan] Scan complete: {} videos in {}ms (dir={})", videos.size(), elapsed, directoryPath);
        return videos;
    }

    /**
     * 收集目录下所有视频文件路径（无锁，纯文件系统操作）
     */
    public List<Path> collectVideoFiles(String directoryPath) {
        Path dir = Paths.get(directoryPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + directoryPath);
        }

        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        String extension = name.substring(name.lastIndexOf('.') + 1);
                        return SUPPORTED_FORMATS.contains(extension);
                    })
                    .toList();
        } catch (Exception e) {
            log.error("[Scan] Failed to walk directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    /**
     * 提取单个视频的基础元数据（无锁，纯内存操作 + 文件读取）
     */
    public Video extractMetadata(Path path, Long libraryId) {
        File file = path.toFile();
        String absolutePath = file.getAbsolutePath();
        String fileName = file.getName();

        // 检查是否已存在（按路径或文件名）
        Video existing = repository.findByFilePath(absolutePath).orElse(null);
        if (existing == null) {
            existing = repository.findByFileName(fileName).orElse(null);
            if (existing != null) {
                log.debug("[Scan] Updating moved video path: {} -> {}", existing.getFilePath(), absolutePath);
                existing.setFilePath(absolutePath);
                existing.setFileName(fileName);
                existing.setFileSize(file.length());
                existing.setFormat(TitleCleaner.getFileExtension(fileName).toUpperCase());
                if (libraryId != null) {
                    existing.setLibraryId(libraryId);
                }
                return existing;
            }
        }

        Video video = existing != null ? existing : new Video();

        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        String cleanTitle = TitleCleaner.cleanForSearch(baseName);
        video.setTitle(cleanTitle.isBlank() ? baseName : cleanTitle);
        video.setFilePath(absolutePath);
        video.setFileName(fileName);
        video.setFileSize(file.length());
        video.setFormat(TitleCleaner.getFileExtension(fileName).toUpperCase());

        // 读取本地 NFO（如果有）
        NfoService.NfoData nfoData = nfoService.readNfoForVideo(path);
        if (nfoData != null) {
            nfoService.applyNfoData(video, nfoData);
            log.debug("[Scan] Applied local NFO metadata: {}", fileName);
        }

        // 从文件名解析集数
        int[] se = parseSeasonEpisode(fileName);
        video.setSeasonNumber(se[0]);
        video.setEpisodeNumber(se[1]);

        if (libraryId != null && video.getLibraryId() == null) {
            video.setLibraryId(libraryId);
        }

        // 推断 mediaType（不依赖 TMDB）
        if (video.getMediaType() == null) {
            String inferredMediaType = inferMediaType(video, libraryId);
            if (inferredMediaType != null) {
                video.setMediaType(inferredMediaType);
            }
        }

        return video;
    }

    /**
     * 从文件名解析季数和集数
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

    /**
     * 判断是否为电视剧集
     */
    public boolean isTvEpisode(Video video) {
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

    /**
     * 根据文件名和库配置推断 mediaType
     */
    public String inferMediaType(Video video, Long libraryId) {
        if (libraryId != null) {
            MediaLibrary library = mediaLibraryService.getLibraryById(libraryId);
            String filter = library.getMediaTypeFilter();
            if (filter != null && !filter.isBlank()) {
                return filter;
            }
        }
        if (isTvEpisode(video)) {
            return "tv";
        }
        return null;
    }

    /**
     * 自动分组系列（基于标题相似度）
     */
    private void autoGroupSeries() {
        List<Video> allVideos = repository.findAll();

        Map<String, List<Video>> grouped = new LinkedHashMap<>();
        for (Video video : allVideos) {
            if (video.getSeries() != null) continue;

            String groupKey;
            if (video.getSeriesName() != null && !video.getSeriesName().isBlank()) {
                groupKey = video.getSeriesName();
            } else {
                groupKey = TitleCleaner.cleanForSearch(video.getTitle());
            }
            if (groupKey == null || groupKey.isBlank()) groupKey = "Unknown";
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(video);
        }

        int groupedCount = 0;
        for (Map.Entry<String, List<Video>> entry : grouped.entrySet()) {
            List<Video> videos = entry.getValue();
            if (videos.size() < 2) continue;

            groupedCount++;
            log.info("[Scan] Auto-grouped series: {} ({} episodes)", entry.getKey(), videos.size());
        }

        if (groupedCount > 0) {
            log.info("[Scan] Auto-grouped {} series from scan", groupedCount);
        }
    }

    private void cleanupInvalidRecords() {
        int removed = 0;
        int pageNum = 0;
        final int pageSize = 100;
        org.springframework.data.domain.Page<Video> page;

        log.debug("[Cleanup] Starting cleanup...");
        do {
            page = repository.findAll(org.springframework.data.domain.PageRequest.of(pageNum++, pageSize));
            List<Video> invalidVideos = page.getContent().stream()
                    .filter(v -> {
                        if (v.getFilePath() == null) return true;
                        return !Files.exists(Paths.get(v.getFilePath()));
                    })
                    .toList();

            if (!invalidVideos.isEmpty()) {
                log.info("[Cleanup] Found {} invalid records on page {}, removing...", invalidVideos.size(), pageNum);
                for (Video video : invalidVideos) {
                    cleanupVideoFiles(video);
                }
                repository.deleteAllById(invalidVideos.stream().map(Video::getId).toList());
                removed += invalidVideos.size();
            }
        } while (page.hasNext());

        if (removed > 0) {
            log.info("[Cleanup] Removed {} invalid records", removed);
        }
    }

    private void cleanupVideoFiles(Video video) {
        try {
            // 删除 NFO 文件
            var nfoPath = nfoService.getNfoPath(video);
            Files.deleteIfExists(nfoPath);
        } catch (Exception ignored) {}
        try {
            // 删除海报
            var posterPath = nfoService.getPosterPath(video);
            Files.deleteIfExists(posterPath);
        } catch (Exception ignored) {}
        try {
            // 删除背景图
            var fanartPath = nfoService.getFanartPath(video);
            Files.deleteIfExists(fanartPath);
        } catch (Exception ignored) {}
        try {
            // 删除演员目录
            var metadataDir = nfoService.getMetadataDir(video);
            var actorsDir = metadataDir.resolve("actors");
            if (Files.isDirectory(actorsDir)) {
                Files.walk(actorsDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    private void cleanupDuplicateSeries() {
        // Delegate to SeriesService if needed
        log.debug("[Cleanup] Checking for duplicate series...");
    }

    /**
     * 查找未刮削的视频
     */
    public List<Video> findUnscraped(Long libraryId) {
        List<Video> allVideos = repository.findUnscrapedVideos();
        if (libraryId != null) {
            return allVideos.stream()
                    .filter(v -> libraryId.equals(v.getLibraryId()))
                    .toList();
        }
        return allVideos;
    }

    /**
     * 按路径查找视频
     */
    public List<Video> findByPath(String path) {
        if (path != null && !path.isBlank()) {
            return repository.findByFilePathContaining(path);
        }
        return repository.findAll();
    }
}
