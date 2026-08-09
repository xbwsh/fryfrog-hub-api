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

        if (updated) {
            seriesService.saveSeries(series);
        }
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
     * 刷新系列所有季的海报（强制重新下载）
     * 用于修复之前下载错误的季海报
     */
    public int refreshSeasonCovers(VideoSeries series) {
        if (series.getTmdbId() == null) {
            log.warn("[Asset] Cannot refresh season covers: no tmdbId for series {}", series.getTitle());
            return 0;
        }

        int refreshed = 0;
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

            // 从 TMDB 获取季海报
            try {
                var seasonDetail = tmdbService.getTvSeasonDetail(series.getTmdbId(), seasonNumber);
                if (seasonDetail != null && seasonDetail.getPosterPath() != null) {
                    String posterUrl = tmdbService.getPosterUrl(seasonDetail.getPosterPath());
                    Path posterPath = seasonDir.resolve("tvshow-poster.jpg");
                    // 强制重新下载
                    downloadCoverImage(posterUrl, posterPath);
                    if (Files.exists(posterPath)) {
                        refreshed++;
                        log.info("[Asset] Refreshed season {} poster for series: {}", seasonNumber, series.getTitle());
                    }
                } else {
                    log.debug("[Asset] No season {} poster on TMDB for series: {}", seasonNumber, series.getTitle());
                }
            } catch (Exception e) {
                log.warn("[Asset] Failed to refresh season {} poster: {}", seasonNumber, e.getMessage());
            }
        }

        return refreshed;
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
            if (java.util.regex.Pattern.matches("第 \\d+ 季", name)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
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
