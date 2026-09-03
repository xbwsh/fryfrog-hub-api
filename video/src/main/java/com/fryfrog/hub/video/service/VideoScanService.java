package com.fryfrog.hub.video.service;

import com.fryfrog.hub.mediacore.service.MediaProbeService;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.util.TitleCleaner;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 视频扫描服务：负责发现视频文件、提取基础元数据、批量入库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoScanService {

    private final VideoRepository repository;
    private final VideoActorRepository actorRepository;
    private final SeriesService seriesService;
    private final NfoService nfoService;
    private final ScrapeProgressService progressService;
    private final MediaLibraryService mediaLibraryService;
    private final MediaProbeService probeService;
    private final VideoCleanupService cleanupService;

    /**
     * 支持的视频格式（主流媒体服务器通用列表，覆盖 Kodi/Jellyfin/Plex 等常见格式）
     */
    static final Set<String> SUPPORTED_FORMATS = Set.of(
            // 现代通用容器
            "mp4", "mkv", "webm", "m4v", "mov", "avi",
            // MPEG 系
            "mpg", "mpeg", "mpe", "m2v", "m1v", "mpv", "vob", "dat",
            // TS/录像系
            "ts", "m2ts", "mts", "m2t", "tp", "trp", "wtv", "dvr-ms",
            // 流媒体/Flash/Windows Media
            "flv", "f4v", "swf", "asf", "wmv",
            // RealMedia
            "rm", "rmvb",
            // 移动端
            "3gp", "3g2",
            // 其他/专业
            "ogv", "ogm", "mxf", "divx", "nsv", "h264", "hevc"
    );

    // ==================== 集数解析正则 ====================

    private static final Pattern SE_EP_PATTERN = Pattern.compile("(?i)S(\\d{1,2})E(\\d{1,4})");
    private static final Pattern SEASON_EPISODE_PATTERN = Pattern.compile("(?i)Season\\s*(\\d{1,2})\\s*Episode\\s*(\\d{1,4})");
    // E/EP 集数要求前导分隔符（行首、点、下划线、空格、连字符、左括号），
    // 避免 "Se7en"、"Re2" 这类标题中的字母+数字被误认为集数
    private static final Pattern EP_PATTERN = Pattern.compile("(?i)(?:^|[._\\-\\s\\[(])EP?(\\d{1,4})\\b");
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

        String module = "scan:" + (libraryId != null ? libraryId : "all");

        // Phase 1: 清理无效记录 + 回填原始文件名
        backfillOriginalFileName();
        cleanupInvalidRecords();
        cleanupDuplicateSeries();

        // Phase 2: 扫描文件系统（无锁）
        List<Path> videoFiles = collectVideoFiles(directoryPath);
        if (videoFiles.isEmpty()) {
            progressService.start(module, 0);
            progressService.finish(module);
            log.debug("[Scan] No video files found in: {}", directoryPath);
            return Collections.emptyList();
        }
        log.debug("[Scan] Found {} video files", videoFiles.size());
        progressService.start(module, videoFiles.size());

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
                progressService.advance(module, path.getFileName().toString(), true);
            } catch (Exception e) {
                log.warn("[Scan] Failed to extract metadata from {}: {}", path.getFileName(), e.getMessage());
                progressService.advance(module, path.getFileName().toString(), false);
            }
        }

        // Phase 4: 批量入库
        repository.saveAll(videos);
        log.debug("[Scan] Saved {} videos to database", videos.size());

        // Phase 5: 自动分组系列
        autoGroupSeries();

        progressService.finish(module);
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
                if (existing.getOriginalFileName() == null) {
                    existing.setOriginalFileName(fileName);
                }
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
        if (video.getOriginalFileName() == null) {
            video.setOriginalFileName(fileName);
        }
        video.setFileSize(file.length());
        video.setFormat(TitleCleaner.getFileExtension(fileName).toUpperCase());

        // 读取本地 NFO（如果有）
        NfoService.NfoData nfoData = nfoService.readNfoForVideo(path);
        if (nfoData != null) {
            nfoService.applyNfoData(video, nfoData);
            log.debug("[Scan] Applied local NFO metadata: {}", fileName);
        }

        // 兜底：poster.jpg / fanart.jpg 优先
        Path videoDir = path.getParent();
        if (videoDir != null) {
            Path poster = videoDir.resolve("poster.jpg");
            if (Files.exists(poster)) {
                video.setCoverArtPath(poster.toString());
            }
            Path fanart = videoDir.resolve("fanart.jpg");
            if (Files.exists(fanart)) {
                video.setBackdropLocalPath(fanart.toString());
            }
        }

        // 从文件名解析集数
        int[] se = parseSeasonEpisode(fileName);
        video.setSeasonNumber(se[0]);
        video.setEpisodeNumber(se[1]);

        // 探测分辨率（仅缺失时，避免重复 I/O）
        if (video.getResolution() == null || video.getResolution().isBlank()) {
            try {
                String resolution = probeService.probeResolution(absolutePath);
                if (resolution != null) {
                    video.setResolution(resolution);
                }
            } catch (Exception e) {
                log.debug("[Scan] Failed to probe resolution for {}: {}", fileName, e.getMessage());
            }
        }

        if (libraryId != null && video.getLibraryId() == null) {
            video.setLibraryId(libraryId);
        }

        // 如果资源库标记为成人，自动设置 isAdult
        if (libraryId != null && !Boolean.TRUE.equals(video.getIsAdult())) {
            MediaLibrary library = mediaLibraryService.getLibraryById(libraryId);
            if (Boolean.TRUE.equals(library.getIsAdult())) {
                video.setIsAdult(true);
            }
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
     * 判断是否为电视剧集。
     * 仅依据文件名中的「强」剧集信号判断（S01E02 / Season-Episode / 分隔符E05 / #12 / 中文紧邻尾数字），
     * 不使用 DASH/TAIL 等弱兜底模式——否则《Deadpool 2》这类尾部数字的电影会被误判为 tv，
     * 进而在自动刮削时被强制走 tv 搜索导致匹配错误。
     */
    public boolean isTvEpisode(Video video) {
        if (video.getFileName() == null) return false;
        String name = video.getFileName();
        if (SE_EP_PATTERN.matcher(name).find()) return true;
        if (SEASON_EPISODE_PATTERN.matcher(name).find()) return true;
        if (EP_PATTERN.matcher(name).find()) return true;
        // 带 $ 结尾锚点的模式需对去掉扩展名后的名字匹配，否则 ".mkv" 会挡住锚点
        String baseName = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.'))
                : name;
        // 匹配 ＃数字 或 #数字 模式
        if (HASH_PATTERN.matcher(baseName).find()) return true;
        // 中文标题紧邻尾数字（"某剧12"）视为剧集；英文尾部数字电影不受影响
        return CJK_TAIL_NUMBER_PATTERN.matcher(baseName).find();
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

    /** 无效记录占比超过该阈值时中止清理（挂载断连时几乎所有文件都会"不存在"） */
    private static final double CLEANUP_INVALID_RATIO_LIMIT = 0.5;

    private void cleanupInvalidRecords() {
        // 先完整收集无效记录、判断占比，再决定是否删除。
        // 防止 NFS/SMB 等挂载瞬时断连时把整库记录连同 NFO/封面一起清掉。
        List<Video> invalidVideos = new ArrayList<>();
        final int pageSize = 100;
        int pageNum = 0;
        Page<Video> page;
        do {
            page = repository.findAll(org.springframework.data.domain.PageRequest.of(pageNum++, pageSize));
            page.getContent().stream()
                    .filter(v -> {
                        if (v.getFilePath() == null) return true;
                        return !Files.exists(Paths.get(v.getFilePath()));
                    })
                    .forEach(invalidVideos::add);
        } while (page.hasNext());

        long total = repository.count();
        if (!invalidVideos.isEmpty() && total > 0
                && invalidVideos.size() / (double) total > CLEANUP_INVALID_RATIO_LIMIT) {
            log.error("[Cleanup] {} of {} video records appear missing (ratio > {}), " +
                            "likely a disconnected/unavailable storage mount. Skipping cleanup this round.",
                    invalidVideos.size(), total, CLEANUP_INVALID_RATIO_LIMIT);
            return;
        }

        if (!invalidVideos.isEmpty()) {
            log.info("[Cleanup] Found {} invalid records, removing...", invalidVideos.size());
            for (Video video : invalidVideos) {
                cleanupVideoFiles(video);
                // 清理关联的演员记录
                actorRepository.deleteAll(actorRepository.findByVideo_Id(video.getId()));
                // 清理截帧候选缓存目录
                cleanupService.deleteFrameCacheDir(video);
            }
            // 清理关联的观看进度与收藏（按 videoId 批量）
            cleanupService.deleteUserData(invalidVideos.stream().map(Video::getId).toList());
            repository.deleteAllById(invalidVideos.stream().map(Video::getId).toList());

            // 清理空系列
            cleanupEmptySeries();
            log.info("[Cleanup] Removed {} invalid records", invalidVideos.size());
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
     * 回填所有 originalFileName 为 null 的视频记录。
     * 对于已被重命名的老视频，回填的是当前（重命名后的）文件名，
     * 作为"已知的最近文件名"保留；新扫描的视频会记录真正的原始文件名。
     */
    private void backfillOriginalFileName() {
        List<Video> allVideos = repository.findAll();
        List<Video> toUpdate = allVideos.stream()
                .filter(v -> v.getOriginalFileName() == null)
                .toList();
        if (toUpdate.isEmpty()) return;
        for (Video v : toUpdate) {
            v.setOriginalFileName(v.getFileName());
        }
        repository.saveAll(toUpdate);
        log.info("[Scan] Backfilled originalFileName for {} videos", toUpdate.size());
    }

    private void cleanupEmptySeries() {
        seriesService.cleanupEmptySeries();
    }

    /**
     * 查找未刮削的视频（排除未识别目录中的视频，它们不参与自动刮削）
     */
    public List<Video> findUnscraped(Long libraryId) {
        List<Video> allVideos = repository.findUnscrapedVideos().stream()
                .filter(v -> !nfoService.isInUnscrapedDir(v))
                .toList();
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
