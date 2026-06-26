package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.ScrapeProgress;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.VideoBindRequest;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.dto.WatchProgressDTO;
import com.fryfrog.hub.video.dto.WatchProgressRequest;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.service.CoverArtService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.TmdbService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "视频管理", description = "视频元数据查询、扫描接口")
public class VideoController {

    private final VideoService service;
    private final TmdbService tmdbService;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final WatchProgressService watchProgressService;
    private final VideoActorRepository actorRepository;
    private final ScrapeProgressService scrapeProgressService;

    @Value("${hub.video.root-paths:./media-library/video}")
    private String rootPathsConfig;

    private List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @GetMapping
    @Operation(summary = "获取所有视频", description = "返回数据库中所有已索引的视频列表")
    public ResponseEntity<ApiResponse<List<VideoDTO>>> getAllVideos() {
        List<Video> videos = service.getAllVideos();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(
                videos.stream().map(Video::getId).toList());
        List<VideoDTO> dtos = videos.stream()
                .map(v -> toDTO(v, progressMap.get(v.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取视频详情", description = "根据ID获取单个视频的详细信息")
    public ResponseEntity<ApiResponse<VideoDTO>> getVideoById(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按标题搜索", description = "根据标题关键词模糊搜索视频")
    public ResponseEntity<ApiResponse<List<VideoDTO>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        List<Video> videos = service.searchByTitle(q);
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(
                videos.stream().map(Video::getId).toList());
        List<VideoDTO> dtos = videos.stream()
                .map(v -> toDTO(v, progressMap.get(v.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/search/director")
    @Operation(summary = "按导演搜索", description = "根据导演名称模糊搜索视频")
    public ResponseEntity<ApiResponse<List<VideoDTO>>> searchByDirector(
            @Parameter(description = "导演名称关键词") @RequestParam String q) {
        List<Video> videos = service.searchByDirector(q);
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(
                videos.stream().map(Video::getId).toList());
        List<VideoDTO> dtos = videos.stream()
                .map(v -> toDTO(v, progressMap.get(v.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回所有已收藏的视频")
    public ResponseEntity<ApiResponse<List<VideoDTO>>> getFavorites() {
        List<Video> videos = service.getFavorites();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(
                videos.stream().map(Video::getId).toList());
        List<VideoDTO> dtos = videos.stream()
                .map(v -> toDTO(v, progressMap.get(v.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置视频的收藏状态")
    public ResponseEntity<ApiResponse<VideoDTO>> setFavorite(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        Video video = service.setFavorite(id, status);
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @PostMapping("/cleanup")
    @Operation(summary = "清理无效记录", description = "删除数据库中文件已不存在的视频记录")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> cleanupInvalidRecords() {
        int removed = service.cleanupInvalidRecords();
        return ResponseEntity.ok(ApiResponse.success(Map.of("removed", removed)));
    }

    @PostMapping("/rescan")
    @Operation(summary = "一键刷新视频库", description = "扫描所有根路径 → 整理文件夹 → 自动刮削未绑定视频")
    public ResponseEntity<ApiResponse<String>> rescan() {
        for (String rootPath : getRootPaths()) {
            try {
                service.scanDirectory(rootPath);
            } catch (Exception e) {
                log.error("Failed to scan video directory {}: {}", rootPath, e.getMessage());
            }
        }
        service.organizeVideos(null);
        service.autoScrapeAll();
        return ResponseEntity.ok(ApiResponse.success("Rescan started: scan → organize → scrape"));
    }

    @GetMapping("/{id:\\d+}/actors")
    @Operation(summary = "获取视频演员列表", description = "返回指定视频的演员信息列表")
    public ResponseEntity<ApiResponse<List<VideoActor>>> getActors(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(actorRepository.findByVideoId(id)));
    }

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面图片", description = "返回视频的封面图片（竖屏海报），无封面时返回标题占位图")
    public ResponseEntity<Resource> getCoverArt(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Path posterPath = nfoService.getPosterPath(video);

        if (Files.exists(posterPath)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new FileSystemResource(posterPath.toFile()));
        }

        try {
            byte[] placeholder = PlaceholderImageGenerator.generate(video.getTitle(), 300, 450);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(placeholder));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id:\\d+}/stream")
    @Operation(summary = "播放视频", description = "流式返回视频文件，支持Range请求实现断点续播")
    public ResponseEntity<Resource> streamVideo(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            jakarta.servlet.http.HttpServletResponse response) {
        Video video = service.getVideoById(id);
        File file = new File(video.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(resource);
    }

    @GetMapping("/tmdb/search")
    @Operation(summary = "搜索TMDB", description = "根据关键词在TMDB上搜索电影和电视剧")
    public ResponseEntity<ApiResponse<List<TmdbSearchResult.TmdbSearchItem>>> searchTmdb(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchFromTmdb(q)));
    }

    @PostMapping("/{id:\\d+}/tmdb/bind")
    @Operation(summary = "绑定TMDB元数据", description = "将TMDB上的元数据绑定到指定视频，同时生成NFO和下载封面")
    public ResponseEntity<ApiResponse<VideoDTO>> bindTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody VideoBindRequest request) {
        Video video = service.scrapeAndBindTmdb(id, request.getTmdbId(), request.getMediaType());
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @PostMapping("/{id:\\d+}/tmdb/unbind")
    @Operation(summary = "解绑TMDB元数据", description = "清除视频的TMDB元数据绑定")
    public ResponseEntity<ApiResponse<VideoDTO>> unbindTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.unbindTmdb(id);
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @PostMapping("/{id:\\d+}/tmdb/unbind-series")
    @Operation(summary = "批量解绑同系列TMDB", description = "解绑同一TMDB ID下的所有视频（电视剧多集场景）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unbindSeriesTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        if (video.getTmdbId() == null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("unbound", 0)));
        }
        int count = service.unbindByTmdbId(video.getTmdbId());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "tmdbId", video.getTmdbId(),
                "unbound", count
        )));
    }

    @PostMapping("/{id:\\d+}/tmdb/refresh")
    @Operation(summary = "刷新TMDB元数据", description = "使用视频已有的TMDB绑定重新刮削元数据")
    public ResponseEntity<ApiResponse<VideoDTO>> refreshTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.rescrapeVideo(id);
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @GetMapping("/scrape/progress")
    @Operation(summary = "刮削进度", description = "返回当前视频刮削任务的进度")
    public ResponseEntity<ApiResponse<ScrapeProgress>> scrapeProgress() {
        return ResponseEntity.ok(ApiResponse.success(scrapeProgressService.getProgress("video")));
    }

    @GetMapping("/tmdb/status")
    @Operation(summary = "检查TMDB配置状态", description = "检查TMDB API是否已正确配置")
    public ResponseEntity<ApiResponse<Boolean>> checkTmdbStatus() {
        return ResponseEntity.ok(ApiResponse.success(tmdbService.isConfigured()));
    }

    @PostMapping("/{id:\\d+}/nfo")
    @Operation(summary = "生成NFO文件", description = "为指定视频生成NFO元数据文件")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateNfo(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        String nfoPath = nfoService.generateNfo(video);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "videoId", String.valueOf(id),
                "nfoPath", nfoPath != null ? nfoPath : "null"
        )));
    }

    @PostMapping("/{id:\\d+}/covers")
    @Operation(summary = "下载封面图片", description = "下载视频的竖屏海报和横屏背景图")
    public ResponseEntity<ApiResponse<Map<String, String>>> downloadCovers(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        boolean success = coverArtService.downloadAllCovers(video);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "videoId", String.valueOf(id),
                "success", String.valueOf(success)
        )));
    }

    @GetMapping("/{id:\\d+}/poster")
    @Operation(summary = "获取竖屏海报", description = "返回视频的竖屏海报图片")
    public ResponseEntity<Resource> getPoster(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        // 优先查找实际文件所在目录
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        String baseName = nfoService.getBaseName(video.getFileName());
        Path posterPath = videoDir.resolve(baseName + "-poster.jpg");
        if (!Files.exists(posterPath)) {
            posterPath = nfoService.getPosterPath(video);
        }
        if (!Files.exists(posterPath)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(posterPath.toFile()));
    }

    @GetMapping("/{id:\\d+}/fanart")
    @Operation(summary = "获取横屏背景图", description = "返回视频的横屏背景图")
    public ResponseEntity<Resource> getFanart(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        String baseName = nfoService.getBaseName(video.getFileName());
        Path fanartPath = videoDir.resolve(baseName + "-fanart.jpg");
        if (!Files.exists(fanartPath)) {
            fanartPath = nfoService.getFanartPath(video);
        }
        if (!Files.exists(fanartPath)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(fanartPath.toFile()));
    }

    @GetMapping("/{id:\\d+}/nfo")
    @Operation(summary = "获取NFO内容", description = "返回视频的NFO文件内容")
    public ResponseEntity<ApiResponse<String>> getNfoContent(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Path nfoPath = nfoService.getNfoPath(video);
        if (!Files.exists(nfoPath)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String content = Files.readString(nfoPath);
            return ResponseEntity.ok(ApiResponse.success(content));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id:\\d+}/progress")
    @Operation(summary = "获取观看进度", description = "获取指定视频的观看进度")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> getProgress(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        WatchProgress progress = watchProgressService.getProgress(id);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "保存观看进度", description = "保存指定视频的观看进度")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> saveProgress(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody WatchProgressRequest request) {
        WatchProgress progress = watchProgressService.saveProgress(id, request.getPosition(), request.getDuration());
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "删除观看进度", description = "清除指定视频的观看进度")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        watchProgressService.deleteProgress(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private VideoDTO toDTO(Video video, WatchProgress progress) {
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        String baseName = nfoService.getBaseName(video.getFileName());
        Path nfoPath = videoDir.resolve(baseName + ".nfo");
        Path posterPath = videoDir.resolve(baseName + "-poster.jpg");
        Path fanartPath = videoDir.resolve(baseName + "-fanart.jpg");
        Path metadataDir = nfoService.getMetadataDir(video);

        VideoDTO dto = VideoDTO.fromEntity(
                video,
                Files.exists(nfoPath) ? nfoPath.toString() : null,
                Files.exists(posterPath) ? posterPath.toString() : null,
                Files.exists(fanartPath) ? fanartPath.toString() : null,
                Files.exists(metadataDir) ? metadataDir.toString() : null
        );

        if (progress != null) {
            dto.setWatchPosition(progress.getPositionSeconds());
            dto.setWatched(progress.getCompleted());
            if (progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
                dto.setWatchProgressPercent(progress.getPositionSeconds() / progress.getDurationSeconds() * 100);
            }
        }

        return dto;
    }

    private VideoDTO toDTO(Video video) {
        return toDTO(video, watchProgressService.getProgress(video.getId()));
    }
}
