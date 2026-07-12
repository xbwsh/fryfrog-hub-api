package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.util.DatabaseWriteLock;
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
    @Transactional(readOnly = true)
    public void batchGenerateAssets(List<Video> videos) {
        log.debug("[Asset] Starting batch asset generation for {} videos", videos.size());

        int success = 0;
        int failed = 0;

        for (Video video : videos) {
            try {
                // 重新获取视频（带 series 关联），避免懒加载问题
                Video freshVideo = videoRepository.findById(video.getId()).orElse(video);
                generateNfoAndCovers(freshVideo);
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

        // 下载封面
        try {
            coverArtService.downloadAllCovers(video);
            log.debug("[Asset] Downloaded covers for: {}", video.getTitle());
        } catch (Exception e) {
            log.warn("[Asset] Failed to download covers for {}: {}", video.getTitle(), e.getMessage());
        }
    }

    /**
     * 下载系列封面到季目录（tvshow-poster.jpg, tvshow-fanart.jpg）
     */
    public void downloadSeriesCovers(VideoSeries series, Path episodeMetadataDir) {
        if (series.getPosterUrl() == null && series.getBackdropUrl() == null) return;

        Path seasonDir = episodeMetadataDir.getParent();
        if (seasonDir == null) return;

        try {
            Files.createDirectories(seasonDir);
        } catch (IOException e) {
            log.warn("[Asset] Failed to create season dir: {}", seasonDir);
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
            DatabaseWriteLock.runInWriteLock(() -> seriesService.saveSeries(series));
        }
    }

    /**
     * 保存演员信息并下载头像
     */
    public void saveActors(Video video, String mediaType, Long tmdbId, Object preloadedDetail) {
        try {
            // 清除旧演员
            DatabaseWriteLock.runInWriteLock(() -> {
                actorRepository.deleteAll(actorRepository.findByVideo_Id(video.getId()));
                actorRepository.flush();
            });

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

        DatabaseWriteLock.runInWriteLock(() -> actorRepository.save(actor));
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
