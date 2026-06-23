package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.dto.WatchProgressDTO;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.WatchProgress;
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
        List<VideoDTO> dtos = service.getAllVideos().stream()
                .map(this::toDTO)
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
        List<VideoDTO> dtos = service.searchByTitle(q).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/search/director")
    @Operation(summary = "按导演搜索", description = "根据导演名称模糊搜索视频")
    public ResponseEntity<ApiResponse<List<VideoDTO>>> searchByDirector(
            @Parameter(description = "导演名称关键词") @RequestParam String q) {
        List<VideoDTO> dtos = service.searchByDirector(q).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回所有已收藏的视频")
    public ResponseEntity<ApiResponse<List<VideoDTO>>> getFavorites() {
        List<VideoDTO> dtos = service.getFavorites().stream()
                .map(this::toDTO)
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

    @PostMapping("/scan")
    @Operation(summary = "扫描视频目录", description = "递归扫描指定目录，提取所有支持格式的视频文件元数据并入库")
    public ResponseEntity<ApiResponse<String>> scanDirectory(
            @Parameter(description = "要扫描的目录路径（必须在配置的根目录内）") @RequestParam String path) {
        validatePath(path);
        service.scanDirectory(path);
        return ResponseEntity.ok(ApiResponse.success("Scan completed", path));
    }

    @PostMapping("/scan-all")
    @Operation(summary = "扫描所有配置的根目录", description = "扫描所有配置的root-paths目录")
    public ResponseEntity<ApiResponse<List<String>>> scanAll() {
        List<String> rootPaths = getRootPaths();
        List<String> scanned = new ArrayList<>();
        for (String rootPath : rootPaths) {
            try {
                service.scanDirectory(rootPath);
                scanned.add(rootPath);
            } catch (Exception e) {
                log.warn("Failed to scan directory {}: {}", rootPath, e.getMessage());
            }
        }
        return ResponseEntity.ok(ApiResponse.success("Scan completed for " + scanned.size() + " directories", scanned));
    }

    @PostMapping("/cleanup")
    @Operation(summary = "清理无效记录", description = "删除数据库中文件已不存在的视频记录")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> cleanupInvalidRecords() {
        int removed = service.cleanupInvalidRecords();
        return ResponseEntity.ok(ApiResponse.success(Map.of("removed", removed)));
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

    private void validatePath(String path) {
        Path requestedPath = Paths.get(path).toAbsolutePath();
        boolean allowed = getRootPaths().stream()
                .anyMatch(root -> requestedPath.startsWith(Paths.get(root).toAbsolutePath()));
        if (!allowed) {
            throw new IllegalArgumentException("Path is outside allowed root paths");
        }
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
            @Parameter(description = "TMDB ID") @RequestParam Long tmdbId,
            @Parameter(description = "媒体类型（movie/tv）") @RequestParam String mediaType) {
        Video video = service.scrapeAndBindTmdb(id, tmdbId, mediaType);
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @PostMapping("/tmdb/auto-scrape")
    @Operation(summary = "自动刮削所有视频", description = "自动为所有未绑定TMDB的视频搜索并绑定元数据")
    public ResponseEntity<ApiResponse<List<VideoDTO>>> autoScrapeAll() {
        List<VideoDTO> dtos = service.autoScrapeAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
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
        Path posterPath = nfoService.getPosterPath(video);
        if (!Files.exists(posterPath)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(posterPath.toFile()));
    }

    @GetMapping("/{id:\\d+}/fanart")
    @Operation(summary = "获取横屏背景图", description = "返回视频的横屏背景图片")
    public ResponseEntity<Resource> getFanart(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Path fanartPath = nfoService.getFanartPath(video);
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
            @Parameter(description = "播放位置（秒）") @RequestParam Double position,
            @Parameter(description = "视频总时长（秒）") @RequestParam Double duration) {
        WatchProgress progress = watchProgressService.saveProgress(id, position, duration);
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "删除观看进度", description = "清除指定视频的观看进度")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        watchProgressService.deleteProgress(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private VideoDTO toDTO(Video video) {
        Path nfoPath = nfoService.getNfoPath(video);
        Path posterPath = nfoService.getPosterPath(video);
        Path fanartPath = nfoService.getFanartPath(video);
        Path metadataDir = nfoService.getMetadataDir(video);

        VideoDTO dto = VideoDTO.fromEntity(
                video,
                Files.exists(nfoPath) ? nfoPath.toString() : null,
                Files.exists(posterPath) ? posterPath.toString() : null,
                Files.exists(fanartPath) ? fanartPath.toString() : null,
                Files.exists(metadataDir) ? metadataDir.toString() : null
        );

        WatchProgress progress = watchProgressService.getProgress(video.getId());
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
