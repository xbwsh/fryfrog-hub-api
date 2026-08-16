package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.dto.TmdbMovieDetail;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 视频资产服务：负责 NFO 生成、封面下载、演员图片下载。
 * 所有 I/O 操作在写锁外执行，只在 DB 更新时短暂持有写锁。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoAssetService {

    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final VideoActorRepository actorRepository;
    private final VideoRepository videoRepository;
    private final SeriesService seriesService;
    private final TmdbService tmdbService;

    @Qualifier("scraperRestTemplate")
    private final RestTemplate scraperRestTemplate;

    /**
     * 批量生成资产（NFO + 封面 + 演员图片）
     */
    @Transactional
    public void batchGenerateAssets(List<Video> videos) {
        batchGenerateAssets(videos, false);
    }

    /**
     * 批量生成资产（NFO + 封面 + 演员图片）
     * @param force 是否强制重新下载封面
     */
    @Transactional
    public void batchGenerateAssets(List<Video> videos, boolean force) {
        log.debug("[Asset] Starting batch asset generation for {} videos", videos.size());

        int success = 0;
        int failed = 0;

        for (Video video : videos) {
            try {
                // 未识别目录中的视频不生成资产（无元数据，避免嵌套目录）
                if (nfoService.isInUnscrapedDir(video)) {
                    continue;
                }
                // 重新获取视频（带 series 关联），避免懒加载问题
                Video freshVideo = videoRepository.findById(video.getId()).orElse(video);
                generateNfoAndCovers(freshVideo, force);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("[Asset] Failed to generate assets for {}: {}", video.getFileName(), e.getMessage());
            }
        }

        log.debug("[Asset] Batch asset generation complete: {} success, {} failed", success, failed);
    }

    /**
     * 为单个视频生成 NFO 和封面
     */
    public void generateNfoAndCovers(Video video) {
        generateNfoAndCovers(video, false);
    }

    /**
     * 为单个视频生成 NFO 和封面
     * 事务内访问懒加载关联（actorEntities/series），避免 LazyInitializationException
     */
    @Transactional
    public void generateNfoAndCovers(Video video, boolean force) {
        // 生成 NFO
        try {
            nfoService.generateNfo(video);
            log.debug("[Asset] Generated NFO for: {}", video.getTitle());
        } catch (Exception e) {
            log.warn("[Asset] Failed to generate NFO for {}: {}", video.getTitle(), e.getMessage());
        }

        // 电视剧同时生成 tvshow.nfo
        if ("tv".equalsIgnoreCase(video.getMediaType()) && video.getSeries() != null) {
            try {
                Path seasonDir = nfoService.getSeasonDir(video);
                nfoService.generateTvShowNfo(video.getSeries(), seasonDir);
                log.debug("[Asset] Generated tvshow.nfo for series: {}", video.getSeries().getTitle());
            } catch (Exception e) {
                log.warn("[Asset] Failed to generate tvshow.nfo for {}: {}", video.getTitle(), e.getMessage());
            }
        }

        // 下载封面并保存路径
        try {
            boolean downloaded = coverArtService.downloadAllCovers(video, force);
            if (downloaded) {
                videoRepository.save(video);
                log.debug("[Asset] Downloaded covers for: {}", video.getTitle());
            }
        } catch (Exception e) {
            log.warn("[Asset] Failed to download covers for {}: {}", video.getTitle(), e.getMessage());
        }

        // 下载系列封面（tvshow-poster.jpg, tvshow-fanart.jpg）
        if ("tv".equalsIgnoreCase(video.getMediaType()) && video.getSeries() != null) {
            try {
                Path episodeDir = nfoService.getMetadataDir(video);
                downloadSeriesCovers(video.getSeries(), episodeDir, force);
            } catch (Exception e) {
                log.debug("[Asset] Failed to download series covers for {}: {}", video.getTitle(), e.getMessage());
            }
        }

        // 下载电影字标 logo（存到电影元数据目录）
        if ("movie".equalsIgnoreCase(video.getMediaType())) {
            try {
                downloadMovieLogo(video, force);
            } catch (Exception e) {
                log.debug("[Asset] Failed to download movie logo for {}: {}", video.getTitle(), e.getMessage());
            }
        }
    }

    /**
     * 下载电影字标 logo（查 TMDB → 下载到电影元数据目录 → 记录本地路径）
     * @return true 表示成功下载并记录了本地路径；false 表示无 logo 或下载失败
     */
    public boolean downloadMovieLogo(Video video) {
        return downloadMovieLogo(video, false);
    }

    /**
     * 下载电影字标 logo（查 TMDB → 下载到电影元数据目录 → 记录本地路径）
     * @param force true 时无视已有文件，强制重新下载
     * @return true 表示成功下载并记录了本地路径；false 表示无 logo 或下载失败
     */
    public boolean downloadMovieLogo(Video video, boolean force) {
        if (video.getTmdbId() == null) return false;

        String filePath = null;
        try {
            String url = tmdbService.getMovieLogoUrl(video.getTmdbId());
            if (url == null) return false;
            filePath = extractFilePath(url);
        } catch (Exception e) {
            log.warn("[Asset] Failed to get movie logo from TMDB for {}: {}", video.getTitle(), e.getMessage());
            return false;
        }
        return downloadMovieLogo(video, filePath, force);
    }

    /**
     * 下载电影指定 file_path 的字标 logo（前端选择设置用）
     * @return true 表示成功下载并记录了本地路径；false 表示下载失败
     */
    public boolean downloadMovieLogo(Video video, String filePath) {
        return downloadMovieLogo(video, filePath, true);
    }

    /**
     * 下载电影指定 file_path 的字标 logo（前端选择设置用）
     * @param force true 时无视已有文件，强制重新下载
     * @return true 表示成功下载并记录了本地路径；false 表示下载失败
     */
    public boolean downloadMovieLogo(Video video, String filePath, boolean force) {
        if (video.getTmdbId() == null || filePath == null || filePath.isBlank()) return false;

        String logoUrl = tmdbService.getLogoUrlByPath(filePath);
        if (logoUrl == null) return false;

        Path metadataDir = nfoService.getMetadataDir(video);
        try {
            Files.createDirectories(metadataDir);
        } catch (IOException e) {
            log.warn("[Asset] Failed to create metadata dir {}: {}", metadataDir, e.getMessage());
            return false;
        }

        video.setLogoUrl(logoUrl);
        Path logoPath = metadataDir.resolve("movie-logo" + extensionOf(logoUrl));
        if (force || !Files.exists(logoPath)) {
            downloadCoverImage(logoUrl, logoPath);
        }
        if (Files.exists(logoPath)) {
            video.setLogoLocalPath(logoPath.toString());
            videoRepository.save(video);
            log.info("[Asset] Downloaded movie logo: {}", video.getTitle());
            return true;
        }
        return false;
    }

    /**
     * 下载系列封面到剧名目录（tvshow-poster.jpg, tvshow-fanart.jpg）
     */
    public void downloadSeriesCovers(VideoSeries series, Path episodeMetadataDir) {
        downloadSeriesCovers(series, episodeMetadataDir, false);
    }

    /**
     * 下载系列封面到剧名目录（tvshow-poster.jpg, tvshow-fanart.jpg）
     * 对于电视剧，会尝试从 TMDB 获取季级别海报
     * @param force true 时无视已有文件，强制重新下载
     */
    public void downloadSeriesCovers(VideoSeries series, Path episodeMetadataDir, boolean force) {
        if (series.getPosterUrl() == null && series.getBackdropUrl() == null) return;

        Path seasonDir = episodeMetadataDir.getParent();
        if (seasonDir == null) return;

        try {
            Files.createDirectories(seasonDir);
        } catch (IOException e) {
            log.warn("[Asset] Failed to create season dir: {}", seasonDir);
            return;
        }

        // 尝试获取季级别海报
        String seasonPosterUrl = null;
        Integer seasonNumber = extractSeasonNumber(seasonDir);
        if (seasonNumber != null && series.getTmdbId() != null) {
            try {
                var seasonDetail = tmdbService.getTvSeasonDetail(series.getTmdbId(), seasonNumber);
                if (seasonDetail != null && seasonDetail.getPosterPath() != null) {
                    seasonPosterUrl = tmdbService.getPosterUrl(seasonDetail.getPosterPath());
                    log.debug("[Asset] Got season {} poster from TMDB: {}", seasonNumber, seasonPosterUrl);
                }
            } catch (Exception e) {
                log.debug("[Asset] Failed to get season poster from TMDB: {}", e.getMessage());
            }
        }

        boolean updated = false;

        // 下载海报（优先使用季级别海报，兜底用系列海报）
        String posterUrl = seasonPosterUrl != null ? seasonPosterUrl : series.getPosterUrl();
        if (posterUrl != null) {
            Path posterPath = seasonDir.resolve("tvshow-poster.jpg");
            if (force || !Files.exists(posterPath)) {
                downloadCoverImage(posterUrl, posterPath);
            }
            if (Files.exists(posterPath) && (force || series.getPosterLocalPath() == null)) {
                series.setPosterLocalPath(posterPath.toString());
                updated = true;
            }
        }

        // 下载背景图（背景图通常使用系列级别的）
        if (series.getBackdropUrl() != null) {
            Path fanartPath = seasonDir.resolve("tvshow-fanart.jpg");
            if (force || !Files.exists(fanartPath)) {
                downloadCoverImage(series.getBackdropUrl(), fanartPath);
            }
            if (Files.exists(fanartPath) && (force || series.getBackdropLocalPath() == null)) {
                series.setBackdropLocalPath(fanartPath.toString());
                updated = true;
            }
        }

        // 下载剧集字标 logo（存到剧名目录）
        if (series.getLogoUrl() != null) {
            Path seriesDir = seasonDir.getParent();
            if (seriesDir != null) {
                Path logoPath = seriesDir.resolve("tvshow-logo" + extensionOf(series.getLogoUrl()));
                if (force || !Files.exists(logoPath)) {
                    downloadCoverImage(series.getLogoUrl(), logoPath);
                }
                if (Files.exists(logoPath) && (force || series.getLogoLocalPath() == null)) {
                    series.setLogoLocalPath(logoPath.toString());
                    updated = true;
                }
            }
        }

        if (updated) {
            seriesService.saveSeries(series);
        }
    }

    /**
     * 从图片 URL 推断文件扩展名（含点），默认 .png
     */
    private String extensionOf(String url) {
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String ext = path.substring(dot);
            if (ext.matches("\\.[a-zA-Z0-9]{2,5}")) return ext;
        }
        return ".png";
    }

    /**
     * 为系列下载剧集字标 logo（查 TMDB → 下载到剧名目录 → 记录本地路径）。
     * 供绑定后资产生成和"补 logo"接口复用。
     * @return true 表示成功下载并记录了本地路径；false 表示无 logo 或下载失败
     */
    public boolean downloadSeriesLogo(VideoSeries series) {
        if (series.getTmdbId() == null) return false;
        String filePath = null;
        try {
            String url = tmdbService.getTvLogoUrl(series.getTmdbId());
            if (url == null) return false;
            filePath = extractFilePath(url);
        } catch (Exception e) {
            log.warn("[Asset] Failed to get logo from TMDB for series {}: {}", series.getTitle(), e.getMessage());
            return false;
        }
        return downloadSeriesLogo(series, filePath);
    }

    /**
     * 为系列下载指定 file_path 的字标 logo（前端选择设置用）。
     * @return true 表示成功下载并记录了本地路径；false 表示下载失败
     */
    public boolean downloadSeriesLogo(VideoSeries series, String filePath) {
        if (series.getTmdbId() == null || filePath == null || filePath.isBlank()) return false;

        String logoUrl = tmdbService.getLogoUrlByPath(filePath);
        if (logoUrl == null) return false;

        Path seriesDir = findSeriesRootDir(series);
        if (seriesDir == null) {
            log.debug("[Asset] Cannot locate series root dir for logo: {}", series.getTitle());
            return false;
        }
        try {
            Files.createDirectories(seriesDir);
        } catch (IOException e) {
            log.warn("[Asset] Failed to create series dir {}: {}", seriesDir, e.getMessage());
            return false;
        }

        series.setLogoUrl(logoUrl);
        Path logoPath = seriesDir.resolve("tvshow-logo" + extensionOf(logoUrl));
        downloadCoverImage(logoUrl, logoPath);
        if (Files.exists(logoPath)) {
            series.setLogoLocalPath(logoPath.toString());
            seriesService.saveSeries(series);
            log.info("[Asset] Downloaded logo for series: {}", series.getTitle());
            return true;
        }
        return false;
    }

    /**
     * 从完整图片 URL 提取 TMDB file_path（如 /abc.png）
     */
    private String extractFilePath(String url) {
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        int slash = path.indexOf("/", "https://".length());
        return slash >= 0 ? path.substring(slash) : path;
    }

    /**
     * 查找系列根目录（剧名目录）：从系列任一视频的季目录向上取父目录
     */
    private Path findSeriesRootDir(VideoSeries series) {
        for (Video video : series.getVideos()) {
            if (video.getFilePath() == null) continue;
            Path seasonDir = nfoService.getSeasonDir(video);
            if (seasonDir != null && seasonDir.getParent() != null) {
                return seasonDir.getParent();
            }
        }
        return null;
    }

    /**
     * 从季目录路径中提取季号（支持 "第 1 季"、"Season 1"、"S01" 等格式）
     */
    private Integer extractSeasonNumber(Path seasonDir) {
        if (seasonDir == null || seasonDir.getFileName() == null) return null;
        String dirName = seasonDir.getFileName().toString();

        // 匹配 "第 1 季" 格式
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("第 (\\d+) 季").matcher(dirName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        // 匹配 "Season 1" 或 "season 1" 格式
        matcher = java.util.regex.Pattern.compile("[Ss]eason\\s*(\\d+)").matcher(dirName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        // 匹配 "S01" 格式
        matcher = java.util.regex.Pattern.compile("[Ss](\\d+)").matcher(dirName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return null;
    }

    /**
     * 刷新系列所有季的资源（强制重新下载）
     * 包括：季海报、每集封面（still_path）、演员信息
     * 用于修复之前下载错误或缺失的资源
     */
    public Map<String, Integer> refreshSeasonAssets(VideoSeries series) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        result.put("seasonPosters", 0);
        result.put("episodeCovers", 0);
        result.put("actors", 0);
        result.put("cleanedOldActorsDirs", 0);
        result.put("logos", 0);

        if (series.getTmdbId() == null) {
            log.warn("[Asset] Cannot refresh season assets: no tmdbId for series {}", series.getTitle());
            return result;
        }

        // 刷新系列剧集字标 logo（剧名目录）
        try {
            if (downloadSeriesLogo(series)) {
                result.merge("logos", 1, Integer::sum);
                log.info("[Asset] Refreshed logo for series: {}", series.getTitle());
            }
        } catch (Exception e) {
            log.warn("[Asset] Failed to refresh logo for series {}: {}", series.getTitle(), e.getMessage());
        }

        // 按季分组视频
        Map<Integer, List<Video>> seasonGroups = new java.util.LinkedHashMap<>();
        for (Video video : series.getVideos()) {
            int season = video.getSeasonNumber() != null ? video.getSeasonNumber() : 1;
            seasonGroups.computeIfAbsent(season, k -> new java.util.ArrayList<>()).add(video);
        }

        for (var entry : seasonGroups.entrySet()) {
            int seasonNumber = entry.getKey();
            List<Video> videos = entry.getValue();
            if (videos.isEmpty()) continue;

            // 获取该季任意一集的元数据目录
            Video sampleVideo = videos.get(0);
            Path metadataDir = nfoService.getMetadataDir(sampleVideo);
            Path seasonDir = metadataDir.getParent();
            if (seasonDir == null) continue;

            try {
                Files.createDirectories(seasonDir);
            } catch (IOException e) {
                log.warn("[Asset] Failed to create season dir: {}", seasonDir);
                continue;
            }

            // 1. 刷新季海报
            try {
                var seasonDetail = tmdbService.getTvSeasonDetail(series.getTmdbId(), seasonNumber);
                if (seasonDetail != null && seasonDetail.getPosterPath() != null) {
                    String posterUrl = tmdbService.getPosterUrl(seasonDetail.getPosterPath());
                    Path posterPath = seasonDir.resolve("tvshow-poster.jpg");
                    downloadCoverImage(posterUrl, posterPath);
                    if (Files.exists(posterPath)) {
                        result.merge("seasonPosters", 1, Integer::sum);
                        log.info("[Asset] Refreshed season {} poster for series: {}", seasonNumber, series.getTitle());
                    }
                }
            } catch (Exception e) {
                log.warn("[Asset] Failed to refresh season {} poster: {}", seasonNumber, e.getMessage());
            }

            // 清理旧的每季 actors 目录（演员图片已移到系列根目录）
            Path oldSeasonActorsDir = seasonDir.resolve("actors");
            if (Files.exists(oldSeasonActorsDir)) {
                try {
                    deleteDirectory(oldSeasonActorsDir);
                    result.merge("cleanedOldActorsDirs", 1, Integer::sum);
                    log.info("[Asset] Cleaned old season actors dir: {}", oldSeasonActorsDir);
                } catch (Exception e) {
                    log.debug("[Asset] Failed to clean old season actors dir {}: {}", oldSeasonActorsDir, e.getMessage());
                }
            }

            // 2. 刷新每集封面和演员
            for (Video video : videos) {
                try {
                    // 刷新集封面（从 TMDB 获取 still_path）
                    if (video.getEpisodeNumber() != null) {
                        var episodeDetail = tmdbService.getTvEpisodeDetail(
                                series.getTmdbId(), seasonNumber, video.getEpisodeNumber());
                        if (episodeDetail != null && episodeDetail.getStillPath() != null) {
                            String stillUrl = tmdbService.getBackdropUrl(episodeDetail.getStillPath());
                            video.setBackdropUrl(stillUrl);
                            // 下载集封面
                            Path fanartPath = nfoService.getFanartPath(video);
                            downloadCoverImage(stillUrl, fanartPath);
                            if (Files.exists(fanartPath)) {
                                video.setBackdropLocalPath(fanartPath.toString());
                                result.merge("episodeCovers", 1, Integer::sum);
                            }
                        }
                    }

                    // 刷新演员信息
                    try {
                        saveActors(video, video.getMediaType(), series.getTmdbId(), null);
                        result.merge("actors", 1, Integer::sum);
                    } catch (Exception e) {
                        log.debug("[Asset] Failed to refresh actors for {}: {}", video.getTitle(), e.getMessage());
                    }

                    videoRepository.save(video);
                } catch (Exception e) {
                    log.warn("[Asset] Failed to refresh assets for episode {}: {}", video.getTitle(), e.getMessage());
                }
            }
        }

        if (series.getLogoLocalPath() != null) {
            seriesService.saveSeries(series);
        }

        return result;
    }

    /**
     * 保存演员信息并下载头像
     */
    public void saveActors(Video video, String mediaType, Long tmdbId, Object preloadedDetail) {
        try {
            // 清除旧演员
            actorRepository.deleteAll(actorRepository.findByVideo_Id(video.getId()));
            actorRepository.flush();

            Path actorsDir = getActorsDir(video, mediaType);
            if (actorsDir == null) return;
            Files.createDirectories(actorsDir);

            // 获取演员列表
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

            // 保存演员
            int count = 0;
            for (Object member : members) {
                try {
                    if (member instanceof TmdbMovieDetail.CastMember cm) {
                        count = saveOneActor(video, actorsDir, count, cm.getName(), cm.getCharacter(), cm.getId(), cm.getProfilePath());
                    } else if (member instanceof TmdbTvDetail.CastMember cm) {
                        count = saveOneActor(video, actorsDir, count, cm.getName(), cm.getCharacter(), cm.getId(), cm.getProfilePath());
                    }
                } catch (Exception e) {
                    log.warn("[Asset] Failed to save actor for video id={}: {}", video.getId(), e.getMessage());
                }
            }
            log.debug("[Asset] Saved {} actors for video id={}", count, video.getId());
        } catch (Exception e) {
            log.warn("[Asset] Failed to save actors for video id={}: {}", video.getId(), e.getMessage());
        }
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
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("User-Agent", "FryfrogHub/0.1.0");
                    HttpEntity<Void> req = new HttpEntity<>(headers);
                    ResponseEntity<byte[]> resp = scraperRestTemplate.exchange(
                            imageUrl, HttpMethod.GET, req, byte[].class);
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        Files.write(actorPath, resp.getBody());
                    }
                } catch (Exception e) {
                    log.warn("[Asset] Failed to download actor image '{}': {}", safeName, e.getMessage());
                }
            }
            actor.setImagePath(actorPath.toAbsolutePath().toString());
        }

        actorRepository.save(actor);
        return count + 1;
    }

    private Path getActorsDir(Video video, String mediaType) {
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        if (videoDir == null) return null;

        if ("tv".equalsIgnoreCase(mediaType)) {
            // 电视剧：演员图片存储到系列根目录（剧名目录），而不是每季目录
            Path seriesDir = findSeriesDir(videoDir);
            if (seriesDir != null) {
                return seriesDir.resolve("actors");
            }
        }
        return videoDir.resolve("actors");
    }

    /**
     * 查找系列根目录（剧名目录）
     * 从视频目录向上查找，直到找到包含季目录的父目录
     */
    private Path findSeriesDir(Path episodeOrVideoDir) {
        Path current = episodeOrVideoDir;
        while (current != null) {
            if (current.getFileName() == null) break;
            String name = current.getFileName().toString();
            // 找到季目录，返回其父目录作为系列目录
            if (java.util.regex.Pattern.matches("第 \\d+ 季", name)) {
                return current.getParent();
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 递归删除目录及其内容
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.debug("[Asset] Failed to delete: {}", path);
                        }
                    });
        }
    }

    private void downloadCoverImage(String imageUrl, Path targetPath) {
        try {
            String fullUrl = imageUrl.startsWith("http") ? imageUrl : "https://image.tmdb.org/t/p/original" + imageUrl;
            var resource = scraperRestTemplate.getForObject(fullUrl, Resource.class);
            if (resource != null) {
                try (var inputStream = resource.getInputStream()) {
                    Files.copy(inputStream, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                log.debug("[Asset] Downloaded series cover: {}", targetPath);
            }
        } catch (Exception e) {
            log.debug("[Asset] Failed to download series cover to {}: {}", targetPath, e.getMessage());
        }
    }
}
