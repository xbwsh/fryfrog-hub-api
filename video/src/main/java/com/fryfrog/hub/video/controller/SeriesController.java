package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ForbiddenException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.video.dto.LibrarySeriesGroupDTO;
import com.fryfrog.hub.video.dto.SeriesDTO;
import com.fryfrog.hub.video.dto.SeriesListDTO;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.service.FavoriteService;
import com.fryfrog.hub.video.service.FrameCaptureService;
import com.fryfrog.hub.video.service.MediaProbeService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.TmdbService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import com.fryfrog.hub.video.service.VideoAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/video/series")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "视频系列管理", description = "视频系列/剧集分组接口")
public class SeriesController {

    private final SeriesService seriesService;
    private final VideoService videoService;
    private final VideoRepository videoRepository;
    private final NfoService nfoService;
    private final WatchProgressService watchProgressService;
    private final FavoriteService favoriteService;
    private final MediaLibraryService mediaLibraryService;
    private final MediaProbeService probeService;
    private final FrameCaptureService frameCaptureService;
    private final TmdbService tmdbService;
    private final VideoAssetService videoAssetService;
    private final VideoActorRepository actorRepository;
    private final com.fryfrog.hub.common.service.ScrapeProgressService scrapeProgressService;
    private final UserService userService;

    private void requireAdmin(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        if (!userService.isAdmin(userId)) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    @GetMapping
    @Operation(summary = "获取所有系列", description = "返回所有视频系列列表（含独立视频），支持分页")
    public ResponseEntity<ApiResponse<PageResponse<SeriesListDTO>>> getAllSeries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        // 全量只加载一次，count 与分页共用，避免两次独立查询结果不一致导致页错位
        List<VideoSeries> allSeries = seriesService.getAllSeries();
        long seriesCount = allSeries.size();
        long standaloneCount = videoRepository.countBySeriesIsNull();
        long total = seriesCount + standaloneCount;

        int start = page * size;
        if (start >= total) {
            return ResponseEntity.ok(ApiResponse.success(
                    PageResponse.of(List.of(), page, size, total)));
        }
        int end = Math.min(start + size, (int) total);

        List<SeriesListDTO> allItems = new ArrayList<>();

        // 计算当前页落在系列和独立视频范围
        long seriesEnd = Math.min(end, seriesCount);
        long standaloneStart = Math.max(0, start - seriesCount);
        long standaloneEnd = Math.max(0, end - seriesCount);

        // 系列（通常数量少，内存分页）
        if (start < seriesCount) {
            Map<Long, Boolean> seriesFav = favoriteService.statusMap(userId, Favorite.TYPE_SERIES,
                    allSeries.stream().map(VideoSeries::getId).toList());
            List<VideoSeries> pagedSeries = allSeries.subList(
                    (int) Math.min(start, seriesCount),
                    (int) Math.min(seriesEnd, allSeries.size()));
            for (VideoSeries s : pagedSeries) {
                allItems.add(SeriesListDTO.fromEntity(s, s.getVideos(), seriesFav.getOrDefault(s.getId(), false)));
            }
        }

        // 独立视频（数据库分页）
        if (standaloneEnd > 0) {
            int saPage = (int) (standaloneStart / size);
            int saOffset = (int) (standaloneStart % size);
            int saLimit = (int) (standaloneEnd - standaloneStart);
            boolean restricted = mediaLibraryService.isRestrictedUser(userId);
            List<Long> allowedIds = mediaLibraryService.getAllowedLibraryIds(userId);
            Pageable saPageable = PageRequest.of(saPage, Math.max(size, saLimit),
                    Sort.by(Sort.Direction.ASC, "title"));
            Page<Video> standalonePage = restricted
                    ? videoRepository.findBySeriesIsNullAndAllowedLibraries(allowedIds, saPageable)
                    : videoRepository.findBySeriesIsNullAndEnabledLibraries(
                            mediaLibraryService.getEnabledLibraryIds(), saPageable);
            List<Video> pagedVideos = standalonePage.getContent();
            if (saOffset > 0 && pagedVideos.size() > saOffset) {
                pagedVideos = pagedVideos.subList(saOffset,
                        Math.min(saOffset + saLimit, pagedVideos.size()));
            } else if (saOffset > 0) {
                pagedVideos = List.of();
            }
            Map<Long, Boolean> videoFav = favoriteService.statusMap(userId, Favorite.TYPE_VIDEO,
                    pagedVideos.stream().map(Video::getId).toList());
            for (Video video : pagedVideos) {
                allItems.add(SeriesListDTO.fromStandaloneVideo(video, videoFav.getOrDefault(video.getId(), false)));
            }
        }

        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(allItems, page, size, total)));
    }

    @GetMapping("/grouped-by-library")
    @Operation(summary = "按资源库分组获取系列", description = "返回按资源库分组的系列和独立视频列表，每个库内部独立分页（默认 50 条）")
    public ResponseEntity<ApiResponse<List<LibrarySeriesGroupDTO>>> getSeriesGroupedByLibrary(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                seriesService.getSeriesGroupedByLibrary(UserContext.currentUserId(request), page, size)));
    }

    @GetMapping("/calendar")
    @Operation(summary = "追更日历", description = "返回在播且有下一集播出日期的系列，按日期升序（前端可按日期渲染日历）")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUpcomingCalendar() {
        List<Map<String, Object>> result = seriesService.getUpcomingCalendar().stream()
                .map(series -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("seriesId", series.getId());
                    item.put("title", series.getTitle());
                    item.put("coverUrl", MediaUrlSigner.sign("/api/v1/video/series/" + series.getId() + "/cover"));
                    item.put("fanartUrl", MediaUrlSigner.sign("/api/v1/video/series/" + series.getId() + "/fanart"));
                    item.put("nextEpisodeDate", series.getNextEpisodeDate());
                    item.put("nextEpisodeNumber", series.getNextEpisodeNumber());
                    return item;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏系列列表", description = "返回当前用户已收藏的系列列表，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<SeriesListDTO>>> getFavoriteSeries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        var result = seriesService.getFavoriteSeriesPage(userId, PageRequest.of(page, size));
        List<SeriesListDTO> dtos = result.getContent().stream()
                .map(s -> SeriesListDTO.fromEntity(s, s.getVideos(), true))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @PutMapping("/{id}/favorite")
    @Operation(summary = "设置系列收藏状态", description = "设置当前用户的系列收藏状态")
    public ResponseEntity<ApiResponse<SeriesDTO>> setSeriesFavorite(
            @Parameter(description = "系列ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        // 收藏目标必须对当前用户可见（至少一集属于可见库），先校验再写入
        VideoSeries target = seriesService.getSeriesById(id).orElse(null);
        if (target != null && target.getVideos().stream().noneMatch(v -> mediaLibraryService.isVisibleToCurrentUser(v.getLibraryId()))) {
            throw new ResourceNotFoundException("Series", "id", id);
        }
        favoriteService.setFavorite(userId, Favorite.TYPE_SERIES, id, status);
        VideoSeries series = seriesService.getSeriesById(id)
                .orElseThrow(() -> new RuntimeException("Series not found: " + id));
        List<Long> videoIds = series.getVideos().stream().map(Video::getId).toList();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(userId, videoIds);
        Map<Long, Boolean> episodesFav = favoriteService.statusMap(userId, Favorite.TYPE_VIDEO, videoIds);
        List<VideoDTO> episodes = series.getVideos().stream()
                .map(video -> toVideoDTO(video, progressMap.get(video.getId()), episodesFav.getOrDefault(video.getId(), false)))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(SeriesDTO.fromEntity(series, episodes, status)));
    }

    @PutMapping("/{id}/metadata")
    @Operation(summary = "编辑系列元数据", description = "手动修改系列的标题、简介、评分、上映日期、类型等元数据（只更新传入的非空字段）")
    public ResponseEntity<ApiResponse<SeriesDTO>> updateSeriesMetadata(
            @Parameter(description = "系列ID") @PathVariable Long id,
            @RequestBody com.fryfrog.hub.video.dto.SeriesMetadataUpdateRequest request,
            HttpServletRequest req) {
        requireAdmin(req);
        long userId = UserContext.currentUserId(req);
        VideoSeries series = seriesService.getSeriesById(id)
                .orElseThrow(() -> new RuntimeException("Series not found: " + id));
        if (series.getVideos() == null || series.getVideos().isEmpty() || series.getVideos().stream().noneMatch(v -> mediaLibraryService.isVisibleToCurrentUser(v.getLibraryId()))) {
            throw new ResourceNotFoundException("Series", "id", id);
        }
        boolean updated = false;

        if (request.getTitle() != null) { series.setTitle(request.getTitle()); updated = true; }
        if (request.getOverview() != null) { series.setOverview(request.getOverview()); updated = true; }
        if (request.getRating() != null) { series.setRating(request.getRating()); updated = true; }
        if (request.getYear() != null) { series.setYear(request.getYear()); updated = true; }
        if (request.getReleaseDate() != null) { series.setReleaseDate(request.getReleaseDate()); updated = true; }
        if (request.getOriginalTitle() != null) { series.setOriginalTitle(request.getOriginalTitle()); updated = true; }
        if (request.getStatus() != null) { series.setStatus(request.getStatus()); updated = true; }

        if (updated) {
            series.setMetadataSource("manual");
            seriesService.saveSeries(series);
            log.info("[Metadata] Updated series id={}: manual metadata applied", id);
        }

        // 返回完整系列详情（含剧集）
        boolean favorite = favoriteService.statusMap(userId, Favorite.TYPE_SERIES, List.of(id))
                .getOrDefault(id, false);
        List<Long> videoIds = series.getVideos().stream().map(Video::getId).toList();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(userId, videoIds);
        Map<Long, Boolean> episodesFav = favoriteService.statusMap(userId, Favorite.TYPE_VIDEO, videoIds);
        List<VideoDTO> episodes = series.getVideos().stream()
                .map(video -> toVideoDTO(video, progressMap.get(video.getId()), episodesFav.getOrDefault(video.getId(), false)))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(SeriesDTO.fromEntity(series, episodes, favorite)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取系列详情", description = "根据ID获取系列详情，包含所有剧集。也支持独立视频ID。可通过type参数指定查询类型（series/standalone），避免ID冲突时查错。")
    public ResponseEntity<ApiResponse<SeriesDTO>> getSeriesById(
            @Parameter(description = "系列ID或独立视频ID") @PathVariable Long id,
            @Parameter(description = "条目类型: series=系列, standalone=独立视频，不传时自动判断")
            @RequestParam(required = false) String type,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        if (!"standalone".equals(type)) {
            var series = seriesService.getSeriesById(id);
            if (series.isPresent()) {
                VideoSeries s = series.get();
                if (mediaLibraryService.isRestrictedUser(userId)
                        && s.getVideos().stream().noneMatch(v -> mediaLibraryService.isVisibleToCurrentUser(v.getLibraryId()))) {
                    throw new ResourceNotFoundException("Series", "id", id);
                }
                boolean favorite = favoriteService.statusMap(userId, Favorite.TYPE_SERIES, List.of(id))
                        .getOrDefault(id, false);
                List<Long> videoIds = s.getVideos().stream().map(Video::getId).toList();
                Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(userId, videoIds);
                Map<Long, Boolean> episodesFav = favoriteService.statusMap(userId, Favorite.TYPE_VIDEO, videoIds);
                List<VideoDTO> episodes = s.getVideos().stream()
                        .map(video -> toVideoDTO(video, progressMap.get(video.getId()), episodesFav.getOrDefault(video.getId(), false)))
                        .collect(Collectors.toList());
                return ResponseEntity.ok(ApiResponse.success(SeriesDTO.fromEntity(s, episodes, favorite)));
            }
        }
        if (!"series".equals(type)) {
            var video = videoService.getVideoById(id);
            if (video != null && video.getSeries() == null) {
                if (mediaLibraryService.isRestrictedUser(userId)
                        && !mediaLibraryService.isVisibleToCurrentUser(video.getLibraryId())) {
                    throw new ResourceNotFoundException("Series", "id", id);
                }
                WatchProgress progress = watchProgressService.getProgress(userId, id);
                boolean favorite = favoriteService.statusMap(userId, Favorite.TYPE_VIDEO, List.of(id))
                        .getOrDefault(id, false);
                return ResponseEntity.ok(ApiResponse.success(SeriesDTO.fromStandaloneVideo(video, toVideoDTO(video, progress, favorite), favorite)));
            }
        }
        throw new RuntimeException("Series not found: " + id);
    }

    @GetMapping("/{id}/cover")
    @Operation(summary = "获取系列封面", description = "返回系列的竖屏封面图片")
    public ResponseEntity<Resource> getSeriesCover(
            @Parameter(description = "系列ID或独立视频ID") @PathVariable Long id) {
        var series = seriesService.getSeriesById(id).orElse(null);
        String title = "Unknown";
        String posterUrl = null;

        if (series != null) {
            title = series.getTitle();
            // 优先使用本地封面文件
            if (series.getPosterLocalPath() != null) {
                Path localPath = Paths.get(series.getPosterLocalPath());
                if (Files.exists(localPath)) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(new FileSystemResource(localPath.toFile()));
                }
            }
            posterUrl = series.getPosterUrl();
        } else {
            var video = videoService.getVideoById(id);
            if (video != null) {
                title = video.getTitle();
                // 优先使用数据库中已存的本地封面路径
                if (video.getCoverArtPath() != null) {
                    Path stored = Paths.get(video.getCoverArtPath());
                    if (Files.exists(stored)) {
                        return ResponseEntity.ok()
                                .contentType(MediaType.IMAGE_JPEG)
                                .body(new FileSystemResource(stored.toFile()));
                    }
                }
                // 兜底：检查元数据目录
                Path videoDir = Paths.get(video.getFilePath()).getParent();
                String baseName = nfoService.getBaseName(video.getFileName());
                Path posterPath = videoDir.resolve(baseName + "-poster.jpg");
                if (Files.exists(posterPath)) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(new FileSystemResource(posterPath.toFile()));
                }
                posterUrl = video.getPosterUrl();
            }
        }
        // fallback: 从远程 URL 下载
        if (posterUrl == null) {
            return generatePlaceholder(title, 300, 450);
        }
        try {
            java.net.URL url = new java.net.URL(posterUrl);
            byte[] imageBytes = url.openStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(imageBytes));
        } catch (Exception e) {
            return generatePlaceholder(title, 300, 450);
        }
    }

    @GetMapping("/{id}/season/{seasonNumber}/cover")
    @Operation(summary = "获取季封面", description = "返回指定季的竖屏封面图片")
    public ResponseEntity<Resource> getSeasonCover(
            @Parameter(description = "系列ID") @PathVariable Long id,
            @Parameter(description = "季数") @PathVariable Integer seasonNumber) {
        var series = seriesService.getSeriesById(id).orElse(null);
        if (series == null) {
            return generatePlaceholder("Unknown", 300, 450);
        }

        // 查找该季的任意一集，用于定位季目录
        var seasonVideo = series.getVideos().stream()
                .filter(v -> seasonNumber.equals(v.getSeasonNumber()))
                .findFirst()
                .orElse(null);

        if (seasonVideo != null) {
            Path seasonDir = nfoService.getSeasonDir(seasonVideo);
            if (seasonDir != null) {
                // 检查本地季封面文件
                Path posterPath = seasonDir.resolve("tvshow-poster.jpg");
                if (Files.exists(posterPath)) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(new FileSystemResource(posterPath.toFile()));
                }
            }
        }

        // 兜底：从 TMDB 获取季海报
        if (series.getTmdbId() != null) {
            try {
                var seasonDetail = tmdbService.getTvSeasonDetail(series.getTmdbId(), seasonNumber);
                if (seasonDetail != null && seasonDetail.getPosterPath() != null) {
                    String posterUrl = tmdbService.getPosterUrl(seasonDetail.getPosterPath());
                    java.net.URL url = new java.net.URL(posterUrl);
                    byte[] imageBytes = url.openStream().readAllBytes();
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(new ByteArrayResource(imageBytes));
                }
            } catch (Exception e) {
                log.debug("Failed to get season poster from TMDB: {}", e.getMessage());
            }
        }

        // 最终兜底：返回系列封面
        return getSeriesCover(id);
    }

    @GetMapping("/{id}/fanart")
    @Operation(summary = "获取系列背景图", description = "返回系列的横屏背景图片")
    public ResponseEntity<Resource> getSeriesFanart(
            @Parameter(description = "系列ID或独立视频ID") @PathVariable Long id) {
        var series = seriesService.getSeriesById(id).orElse(null);
        String title = "Unknown";
        String backdropUrl = null;

        if (series != null) {
            title = series.getTitle();
            // 优先使用本地背景图文件
            if (series.getBackdropLocalPath() != null) {
                Path localPath = Paths.get(series.getBackdropLocalPath());
                if (Files.exists(localPath)) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(new FileSystemResource(localPath.toFile()));
                }
            }
            backdropUrl = series.getBackdropUrl();
        } else {
            var video = videoService.getVideoById(id);
            if (video != null) {
                title = video.getTitle();
                // 优先使用数据库中已存的本地背景图路径
                if (video.getBackdropLocalPath() != null) {
                    Path stored = Paths.get(video.getBackdropLocalPath());
                    if (Files.exists(stored)) {
                        return ResponseEntity.ok()
                                .contentType(MediaType.IMAGE_JPEG)
                                .body(new FileSystemResource(stored.toFile()));
                    }
                }
                // 兜底：检查元数据目录
                Path videoDir = Paths.get(video.getFilePath()).getParent();
                String baseName = nfoService.getBaseName(video.getFileName());
                Path fanartPath = videoDir.resolve(baseName + "-fanart.jpg");
                if (!Files.exists(fanartPath)) {
                    fanartPath = nfoService.getFanartPath(video);
                }
                if (Files.exists(fanartPath)) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(new FileSystemResource(fanartPath.toFile()));
                }
                backdropUrl = video.getBackdropUrl();
            }
        }
        // fallback: 从远程 URL 下载
        if (backdropUrl == null) {
            return generatePlaceholder(title, 1920, 1080);
        }
        try {
            java.net.URL url = new java.net.URL(backdropUrl);
            byte[] imageBytes = url.openStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(imageBytes));
        } catch (Exception e) {
            return generatePlaceholder(title, 1920, 1080);
        }
    }

    @GetMapping("/{id}/logo")
    @Operation(summary = "获取系列Logo", description = "返回系列的剧集字标图片（本地优先，远程 TMDB 兜底）")
    public ResponseEntity<Resource> getSeriesLogo(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        var series = seriesService.getSeriesById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.notFound().build();
        }

        // 优先使用本地 logo 文件
        if (series.getLogoLocalPath() != null) {
            Path localPath = Paths.get(series.getLogoLocalPath());
            if (Files.exists(localPath)) {
                return ResponseEntity.ok()
                        .contentType(logoMediaType(localPath.getFileName().toString()))
                        .body(new FileSystemResource(localPath.toFile()));
            }
        }

        // 兜底：从 TMDB 远程下载
        String logoUrl = series.getLogoUrl();
        if (logoUrl == null && series.getTmdbId() != null) {
            logoUrl = tmdbService.getTvLogoUrl(series.getTmdbId());
        }
        if (logoUrl == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            java.net.URL url = new java.net.URL(logoUrl);
            byte[] imageBytes = url.openStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(logoMediaType(logoUrl))
                    .body(new ByteArrayResource(imageBytes));
        } catch (Exception e) {
            log.debug("Failed to download series logo from TMDB: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType logoMediaType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        return MediaType.IMAGE_JPEG;
    }

    @GetMapping("/{id}/logo-options")
    @Operation(summary = "查询系列Logo选项", description = "返回该系列在 TMDB 上的所有字标 logo 选项（按投票数降序），供用户选择设置")
    public ResponseEntity<ApiResponse<List<com.fryfrog.hub.video.dto.LogoOption>>> getSeriesLogoOptions(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        var series = seriesService.getSeriesById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列不存在: " + id));
        }
        if (series.getTmdbId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列没有 TMDB ID，无法查询 logo"));
        }
        return ResponseEntity.ok(ApiResponse.success(tmdbService.getTvLogoOptions(series.getTmdbId())));
    }

    @PostMapping("/{id}/logo")
    @Operation(summary = "设置系列Logo", description = "从查询到的 logo 选项中选一个设置（body 传 filePath）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setSeriesLogo(
            @Parameter(description = "系列ID") @PathVariable Long id,
            @RequestBody com.fryfrog.hub.video.dto.LogoSelectRequest request,
            HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        if (request.getFilePath() == null || request.getFilePath().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("filePath 不能为空"));
        }

        VideoSeries series = seriesService.getSeriesById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列不存在: " + id));
        }
        if (series.getVideos() == null || series.getVideos().isEmpty() || series.getVideos().stream().noneMatch(v -> mediaLibraryService.isVisibleToCurrentUser(v.getLibraryId()))) {
            throw new ResourceNotFoundException("Series", "id", id);
        }
        if (series.getTmdbId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列没有 TMDB ID，无法设置 logo"));
        }

        try {
            boolean downloaded = videoAssetService.downloadSeriesLogo(series, request.getFilePath());
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("seriesId", id);
            result.put("seriesTitle", series.getTitle());
            result.put("downloaded", downloaded);
            result.put("logoUrl", series.getLogoApiUrl());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to set logo for series {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("设置 logo 失败: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/frames/select")
    @Operation(summary = "从单集截帧设置系列横屏背景图", description = "前端先调单集接口生成候选帧并预览，用户选定某集某帧后，将该帧设为系列横屏背景图")
    public ResponseEntity<ApiResponse<Map<String, Object>>> selectSeriesFanart(
            @Parameter(description = "系列ID") @PathVariable Long id,
            @RequestBody com.fryfrog.hub.video.dto.SeriesFrameSelectRequest request) {
        if (request.getVideoId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("videoId 不能为空"));
        }

        VideoSeries series = seriesService.getSeriesById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列不存在: " + id));
        }
        Video video = videoService.getVideoById(request.getVideoId());
        if (video == null || video.getSeries() == null || !video.getSeries().getId().equals(id)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("该视频不属于此系列"));
        }

        // 候选帧缓存目录（与单集接口一致：视频同目录 .frames-{videoId}/）
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        Path framePath = videoDir.resolve(".frames-" + video.getId()).resolve("frame-" + request.getIndex() + ".jpg");
        if (!Files.exists(framePath)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("候选帧不存在，请先调用单集生成接口"));
        }

        try {
            // 系列横屏背景图保存到视频同目录（与单集 fanart-frame-v3 命名区分）
            String baseName = nfoService.getBaseName(video.getFileName());
            Path outputPath = videoDir.resolve(baseName + "-series-fanart.jpg");

            // 从该时间点重新截取 1920x1080
            double duration = probeService.getDurationSeconds(video.getFilePath());
            double[] ratios = {0.12, 0.28, 0.44, 0.60, 0.76, 0.88};
            double pos = duration > 0 ? duration * ratios[request.getIndex()] : 30 + request.getIndex() * 30;
            boolean ok = frameCaptureService.captureFrameAt(
                    video.getFilePath(), outputPath.toString(), 1920, 1080, pos);
            if (!ok) {
                Files.copy(framePath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }

            series.setBackdropLocalPath(outputPath.toString());
            seriesService.saveSeries(series);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("seriesId", id);
            result.put("videoId", request.getVideoId());
            result.put("path", outputPath.toString());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to set series fanart for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("系列背景图设置失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/actors")
    @Operation(summary = "获取系列演员列表", description = "返回该系列的演员列表（按首集去重，兼容每集重复存储）")
    public ResponseEntity<ApiResponse<List<VideoActor>>> getSeriesActors(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        var seriesOpt = seriesService.getSeriesById(id);
        if (seriesOpt.isEmpty()) {
            throw new ResourceNotFoundException("Series", "id", id);
        }
        // 按系列维度聚合去重（sourceActorId 去重，保留首次出现顺序）
        List<VideoActor> all = actorRepository.findByVideo_Series_Id(id);
        Map<Long, VideoActor> dedup = new java.util.LinkedHashMap<>();
        List<VideoActor> noSourceId = new java.util.ArrayList<>();
        for (VideoActor a : all) {
            if (a.getSourceActorId() != null) {
                dedup.putIfAbsent(a.getSourceActorId(), a);
            } else {
                noSourceId.add(a);
            }
        }
        List<VideoActor> result = new java.util.ArrayList<>(dedup.values());
        result.addAll(noSourceId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id}/refresh-season-covers")
    @Operation(summary = "刷新季资源", description = "重新下载该系列所有季的资源（季海报、每集封面、演员信息）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshSeasonCovers(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        VideoSeries series = seriesService.getSeriesById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列不存在: " + id));
        }

        if (series.getTmdbId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列没有 TMDB ID，无法获取资源"));
        }

        try {
            Map<String, Integer> refreshResult = videoAssetService.refreshSeasonAssets(series);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("seriesId", id);
            result.put("seriesTitle", series.getTitle());
            result.put("refreshedSeasonPosters", refreshResult.getOrDefault("seasonPosters", 0));
            result.put("refreshedEpisodeCovers", refreshResult.getOrDefault("episodeCovers", 0));
            result.put("refreshedActors", refreshResult.getOrDefault("actors", 0));
            result.put("cleanedOldActorsDirs", refreshResult.getOrDefault("cleanedOldActorsDirs", 0));
            result.put("totalSeasons", series.getNumberOfSeasons());
            result.put("totalEpisodes", series.getEpisodeCount());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to refresh season assets for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("刷新季资源失败: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/refresh-logo")
    @Operation(summary = "补全系列Logo", description = "为已绑定 TMDB 的系列从 TMDB 获取并下载剧集字标 logo（本地已有则跳过）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshSeriesLogo(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        VideoSeries series = seriesService.getSeriesById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列不存在: " + id));
        }
        if (series.getTmdbId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("系列没有 TMDB ID，无法获取 logo"));
        }

        try {
            boolean downloaded = videoAssetService.downloadSeriesLogo(series);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("seriesId", id);
            result.put("seriesTitle", series.getTitle());
            result.put("downloaded", downloaded);
            result.put("logoUrl", series.getLogoApiUrl());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to refresh logo for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("补全 logo 失败: " + e.getMessage()));
        }
    }

    @PostMapping("/refresh-all-season-covers")
    @Operation(summary = "批量刷新所有系列季资源", description = "异步刷新所有系列的季海报、集封面和演员信息（仅处理启用刮削的媒体库），进度查询见 scrape/progress?module=season-covers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshAllSeasonCovers() {
        // 获取启用刮削的媒体库 ID
        List<Long> scrapeEnabledLibraryIds = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> Boolean.TRUE.equals(lib.getEnableScraping()))
                .map(lib -> lib.getId())
                .toList();

        List<VideoSeries> allSeries = seriesService.getAllSeries();
        List<VideoSeries> seriesWithTmdb = allSeries.stream()
                .filter(s -> s.getTmdbId() != null)
                // 只处理启用刮削的媒体库中的系列
                .filter(s -> s.getVideos().stream()
                        .anyMatch(v -> v.getLibraryId() != null && scrapeEnabledLibraryIds.contains(v.getLibraryId())))
                .toList();

        String module = "season-covers";
        scrapeProgressService.start(module, seriesWithTmdb.size());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totalSeries", seriesWithTmdb.size());
        result.put("status", "submitted");
        result.put("message", "批量刷新任务已提交，正在后台执行");
        result.put("module", module);

        // 异步执行批量刷新
        Thread.startVirtualThread(() -> {
            int completed = 0;
            int failed = 0;
            Map<String, Integer> totals = new java.util.LinkedHashMap<>();
            totals.put("seasonPosters", 0);
            totals.put("episodeCovers", 0);
            totals.put("actors", 0);
            totals.put("cleanedOldActorsDirs", 0);

            for (VideoSeries series : seriesWithTmdb) {
                try {
                    Map<String, Integer> refreshResult = videoAssetService.refreshSeasonAssets(series);
                    refreshResult.forEach((key, value) -> totals.merge(key, value, Integer::sum));
                    completed++;
                    scrapeProgressService.advance(module, series.getTitle(), true);
                    log.info("[Batch] Completed series {}/{}: {}", completed, seriesWithTmdb.size(), series.getTitle());
                } catch (Exception e) {
                    failed++;
                    scrapeProgressService.advance(module, series.getTitle(), false);
                    log.warn("[Batch] Failed series {}: {}", series.getTitle(), e.getMessage());
                }
            }

            scrapeProgressService.finish(module);
            log.info("[Batch] All done: {} completed, {} failed, results: {}", completed, failed, totals);
        });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private ResponseEntity<Resource> generatePlaceholder(String title, int width, int height) {
        try {
            byte[] placeholder = PlaceholderImageGenerator.generate(title, width, height);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(placeholder));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private VideoDTO toVideoDTO(Video video, WatchProgress progress, boolean favorite) {
        boolean hasNfo = Files.exists(nfoService.getNfoPath(video));
        boolean hasPoster = Files.exists(nfoService.getPosterPath(video));
        boolean hasFanart = Files.exists(nfoService.getFanartPath(video));
        boolean hasMetadataDir = Files.exists(nfoService.getMetadataDir(video));
        VideoDTO dto = VideoDTO.fromEntity(video, hasNfo, hasPoster, hasFanart, hasMetadataDir, favorite);

        if (progress != null) {
            dto.setWatchPosition(progress.getPositionSeconds());
            dto.setWatched(progress.getCompleted());
            if (progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
                dto.setWatchProgressPercent(progress.getPositionSeconds() / progress.getDurationSeconds() * 100);
            }
        }

        return dto;
    }
}
