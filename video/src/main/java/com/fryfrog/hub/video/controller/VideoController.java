package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.video.dto.UpdatePositionRequest;
import com.fryfrog.hub.video.dto.UpdateWatchedRequest;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.dto.VideoMetadataUpdateRequest;
import com.fryfrog.hub.video.dto.WatchProgressDTO;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "视频管理", description = "视频元数据查询、扫描接口")
public class VideoController {

    private final VideoService service;
    private final NfoService nfoService;
    private final WatchProgressService watchProgressService;
    private final VideoActorRepository actorRepository;
    private final VideoRepository videoRepository;
    private final SeriesService seriesService;
    private final VideoControllerSupport support;

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取视频详情", description = "根据ID获取单个视频的详细信息")
    public ResponseEntity<ApiResponse<VideoDTO>> getVideoById(
            @Parameter(description = "视频ID") @PathVariable Long id,
            HttpServletRequest request) {
        Video video = service.getVideoById(id);
        if (!support.isLibraryVisibleToCurrentUser(video.getLibraryId())) {
            throw new ResourceNotFoundException("Video", "id", id);
        }
        return ResponseEntity.ok(ApiResponse.success(support.toDTO(video, request)));
    }

    @PutMapping("/{id:\\d+}/metadata")
    @Operation(summary = "编辑视频元数据", description = "手动修改视频的标题、简介、评分、上映日期、类型等元数据（只更新传入的非空字段）")
    public ResponseEntity<ApiResponse<VideoDTO>> updateVideoMetadata(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody VideoMetadataUpdateRequest request,
            HttpServletRequest req) {
        Video video = service.getVideoById(id);
        if (!support.isLibraryVisibleToCurrentUser(video.getLibraryId())) {
            throw new ResourceNotFoundException("Video", "id", id);
        }
        boolean updated = false;

        if (request.getTitle() != null) { video.setTitle(request.getTitle()); updated = true; }
        if (request.getOverview() != null) { video.setOverview(request.getOverview()); updated = true; }
        if (request.getRating() != null) { video.setRating(request.getRating()); updated = true; }
        if (request.getYear() != null) { video.setYear(request.getYear()); updated = true; }
        if (request.getReleaseDate() != null) { video.setReleaseDate(request.getReleaseDate()); updated = true; }
        if (request.getGenre() != null) { video.setGenre(request.getGenre()); updated = true; }
        if (request.getDirector() != null) { video.setDirector(request.getDirector()); updated = true; }
        if (request.getActors() != null) { video.setActors(request.getActors()); updated = true; }
        if (request.getOriginalTitle() != null) { video.setOriginalTitle(request.getOriginalTitle()); updated = true; }
        if (request.getTags() != null) { video.setTags(request.getTags()); updated = true; }

        if (updated) {
            video.setMetadataSource("manual");
            videoRepository.save(video);
            log.info("[Metadata] Updated video id={}: manual metadata applied", id);
        }
        return ResponseEntity.ok(ApiResponse.success(support.toDTO(video, req)));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按标题搜索", description = "根据标题关键词模糊搜索视频")
    public ResponseEntity<ApiResponse<PageResponse<VideoDTO>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        var result = service.searchByTitle(q, page, size);
        return ResponseEntity.ok(ApiResponse.success(support.toPageDTO(result.getContent(), page, size, result.getTotalElements(), userId)));
    }

    @GetMapping("/search/director")
    @Operation(summary = "按导演搜索", description = "根据导演名称模糊搜索视频")
    public ResponseEntity<ApiResponse<PageResponse<VideoDTO>>> searchByDirector(
            @Parameter(description = "导演名称关键词") @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        var result = service.searchByDirector(q, page, size);
        return ResponseEntity.ok(ApiResponse.success(support.toPageDTO(result.getContent(), page, size, result.getTotalElements(), userId)));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回当前用户已收藏的视频，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<VideoDTO>>> getFavorites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        var result = service.getFavorites(userId, page, size);
        return ResponseEntity.ok(ApiResponse.success(support.toPageDTO(result.getContent(), page, size, result.getTotalElements(), userId)));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置当前用户的视频收藏状态")
    public ResponseEntity<ApiResponse<VideoDTO>> setFavorite(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status,
            HttpServletRequest request) {
        // 收藏目标必须对当前用户可见，防止受限用户借 ID 探测其他库内容
        support.requireLibraryVisible(service.getVideoById(id).getLibraryId(), id, "Video");
        long userId = UserContext.currentUserId(request);
        service.setFavorite(userId, id, status);
        return ResponseEntity.ok(ApiResponse.success(support.toDTO(service.getVideoById(id), request)));
    }

    @GetMapping("/actor/{actorId:\\d+}/works")
    @Operation(summary = "获取演员作品列表", description = "返回指定演员出演的视频列表，按 TMDB 演员ID优先、姓名兜底聚合，已按当前用户可见媒体库过滤，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<VideoDTO>>> getActorWorks(
            @Parameter(description = "演员ID") @PathVariable Long actorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        VideoActor actor = actorRepository.findById(actorId).orElse(null);
        if (actor == null) {
            throw new ResourceNotFoundException("VideoActor", "id", actorId);
        }
        Set<Long> videoIds = new LinkedHashSet<>();
        if (actor.getSourceActorId() != null) {
            actorRepository.findBySourceActorId(actor.getSourceActorId())
                    .forEach(a -> videoIds.add(a.getVideo().getId()));
        }
        if (actor.getName() != null && !actor.getName().isBlank()) {
            actorRepository.findByNameIgnoreCase(actor.getName())
                    .forEach(a -> videoIds.add(a.getVideo().getId()));
        }
        if (videoIds.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(PageResponse.of(List.of(), page, size, 0)));
        }
        List<Video> videos = videoRepository.findByIdIn(videoIds).stream()
                .filter(v -> support.isLibraryVisibleToCurrentUser(v.getLibraryId()))
                .sorted(Comparator
                        .comparing(Video::getYear, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Video::getTitle, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int total = videos.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<Video> slice = videos.subList(from, to);
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(support.toPageDTO(slice, page, size, total, userId)));
    }

    @GetMapping("/{id:\\d+}/actors")
    @Operation(summary = "获取视频演员列表", description = "返回指定视频的演员信息列表，兼容系列ID（剧集用系列ID时聚合去重）")
    public ResponseEntity<ApiResponse<List<VideoActor>>> getActors(
            @Parameter(description = "视频ID或系列ID") @PathVariable Long id) {
        // 优先按视频查询（带权限校验，与远端新增校验保持一致）
        try {
            Video video = service.getVideoById(id);
            support.requireLibraryVisible(video.getLibraryId(), id, "Video");
            List<VideoActor> actors = actorRepository.findByVideo_Id(id);
            if (!actors.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(actors));
            }
            // 视频存在但演员为空，继续尝试系列聚合（兼容ID重叠或旧数据）
        } catch (ResourceNotFoundException ignored) {
            // 非视频ID，尝试系列分支
        }
        var seriesOpt = seriesService.getSeriesById(id);
        if (seriesOpt.isPresent()) {
            List<VideoActor> seriesActors = actorRepository.findByVideo_Series_Id(id);
            Map<Long, VideoActor> dedup = new java.util.LinkedHashMap<>();
            List<VideoActor> noSourceId = new ArrayList<>();
            for (VideoActor a : seriesActors) {
                if (a.getSourceActorId() != null) {
                    dedup.putIfAbsent(a.getSourceActorId(), a);
                } else {
                    noSourceId.add(a);
                }
            }
            List<VideoActor> result = new ArrayList<>(dedup.values());
            result.addAll(noSourceId);
            return ResponseEntity.ok(ApiResponse.success(result));
        }
        // 既非视频也非系列：若是视频（但演员为空）已在上方返回；此处兜底抛404以保持远端行为
        Video video = service.getVideoById(id);
        support.requireLibraryVisible(video.getLibraryId(), id, "Video");
        return ResponseEntity.ok(ApiResponse.success(actorRepository.findByVideo_Id(id)));
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
    @Operation(summary = "获取观看进度", description = "获取当前用户指定视频的观看进度")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> getProgress(
            @Parameter(description = "视频ID") @PathVariable Long id,
            HttpServletRequest request) {
        support.requireLibraryVisible(service.getVideoById(id).getLibraryId(), id, "Video");
        long userId = UserContext.currentUserId(request);
        WatchProgress progress = watchProgressService.getProgress(userId, id);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "更新播放位置", description = "轻量更新播放位置，可选更新总时长。退出播放器时调用，自动判定是否看完")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> updatePosition(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Valid @RequestBody UpdatePositionRequest request,
            HttpServletRequest req) {
        support.requireLibraryVisible(service.getVideoById(id).getLibraryId(), id, "Video");
        long userId = UserContext.currentUserId(req);
        WatchProgress progress = watchProgressService.updatePosition(userId, id, request.getPosition(), request.getDuration());
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/watched")
    @Operation(summary = "设置已观看状态", description = "标记视频为已看完或未看完")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> updateWatched(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody UpdateWatchedRequest request,
            HttpServletRequest req) {
        support.requireLibraryVisible(service.getVideoById(id).getLibraryId(), id, "Video");
        long userId = UserContext.currentUserId(req);
        boolean completed = request != null && Boolean.TRUE.equals(request.getCompleted());
        WatchProgress progress = watchProgressService.updateWatched(userId, id, completed);
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "清除观看进度", description = "删除当前用户指定视频的观看进度记录")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(
            @Parameter(description = "视频ID") @PathVariable Long id,
            HttpServletRequest request) {
        support.requireLibraryVisible(service.getVideoById(id).getLibraryId(), id, "Video");
        long userId = UserContext.currentUserId(request);
        watchProgressService.deleteProgress(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
