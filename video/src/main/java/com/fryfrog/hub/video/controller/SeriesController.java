package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.video.dto.SeriesDTO;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/video/series")
@RequiredArgsConstructor
@Tag(name = "视频系列管理", description = "视频系列/剧集分组接口")
public class SeriesController {

    private final SeriesService seriesService;
    private final VideoService videoService;
    private final NfoService nfoService;
    private final WatchProgressService watchProgressService;

    @GetMapping
    @Operation(summary = "获取所有系列", description = "返回所有视频系列列表（含独立电影）")
    public ResponseEntity<ApiResponse<List<SeriesDTO>>> getAllSeries() {
        List<SeriesDTO> dtos = new ArrayList<>();

        // 收集所有视频 ID
        List<Long> allVideoIds = new ArrayList<>();
        for (VideoSeries series : seriesService.getAllSeries()) {
            series.getVideos().forEach(v -> allVideoIds.add(v.getId()));
        }
        videoService.getAllVideos().stream()
                .filter(v -> v.getSeries() == null)
                .forEach(v -> allVideoIds.add(v.getId()));
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(allVideoIds);

        // 系列剧集
        for (VideoSeries series : seriesService.getAllSeries()) {
            List<VideoDTO> episodes = series.getVideos().stream()
                    .map(v -> toVideoDTO(v, progressMap.get(v.getId())))
                    .collect(Collectors.toList());
            dtos.add(SeriesDTO.fromEntity(series, episodes));
        }

        // 独立视频（不属于任何系列的，如电影）
        List<Video> standaloneVideos = videoService.getAllVideos().stream()
                .filter(v -> v.getSeries() == null)
                .sorted(Comparator.comparing(Video::getTitle))
                .toList();
        for (Video video : standaloneVideos) {
            dtos.add(SeriesDTO.fromStandaloneVideo(video, toVideoDTO(video, progressMap.get(video.getId()))));
        }

        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取系列详情", description = "根据ID获取系列详情，包含所有剧集。也支持独立视频ID")
    public ResponseEntity<ApiResponse<SeriesDTO>> getSeriesById(
            @Parameter(description = "系列ID或独立视频ID") @PathVariable Long id) {
        // 先尝试查系列
        var series = seriesService.getSeriesById(id);
        if (series.isPresent()) {
            VideoSeries s = series.get();
            List<Long> videoIds = s.getVideos().stream().map(Video::getId).toList();
            Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(videoIds);
            List<VideoDTO> episodes = s.getVideos().stream()
                    .map(video -> toVideoDTO(video, progressMap.get(video.getId())))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(SeriesDTO.fromEntity(s, episodes)));
        }
        // fallback：查是否为独立视频（getAllSeries 返回时用 videoId 作为 DTO id）
        var video = videoService.getVideoById(id);
        if (video != null && video.getSeries() == null) {
            WatchProgress progress = watchProgressService.getProgress(id);
            return ResponseEntity.ok(ApiResponse.success(SeriesDTO.fromStandaloneVideo(video, toVideoDTO(video, progress))));
        }
        throw new RuntimeException("Series not found: " + id);
    }

    @GetMapping("/{id}/cover")
    @Operation(summary = "获取系列封面", description = "返回系列的竖屏封面图片")
    public ResponseEntity<Resource> getSeriesCover(
            @Parameter(description = "系列ID或独立视频ID") @PathVariable Long id) {
        var series = seriesService.getSeriesById(id).orElse(null);
        String posterUrl = null;
        String title = "Unknown";
        if (series != null) {
            posterUrl = series.getPosterUrl();
            title = series.getTitle();
        } else {
            var video = videoService.getVideoById(id);
            if (video != null) {
                posterUrl = video.getPosterUrl();
                title = video.getTitle();
            }
        }
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

    @GetMapping("/{id}/fanart")
    @Operation(summary = "获取系列背景图", description = "返回系列的横屏背景图片")
    public ResponseEntity<Resource> getSeriesFanart(
            @Parameter(description = "系列ID或独立视频ID") @PathVariable Long id) {
        var series = seriesService.getSeriesById(id).orElse(null);
        String backdropUrl = null;
        String title = "Unknown";
        if (series != null) {
            backdropUrl = series.getBackdropUrl();
            title = series.getTitle();
        } else {
            var video = videoService.getVideoById(id);
            if (video != null) {
                backdropUrl = video.getBackdropUrl();
                title = video.getTitle();
            }
        }
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

    private VideoDTO toVideoDTO(Video video, WatchProgress progress) {
        boolean hasNfo = Files.exists(nfoService.getNfoPath(video));
        boolean hasPoster = Files.exists(nfoService.getPosterPath(video));
        boolean hasFanart = Files.exists(nfoService.getFanartPath(video));
        boolean hasMetadataDir = Files.exists(nfoService.getMetadataDir(video));
        VideoDTO dto = VideoDTO.fromEntity(video, hasNfo, hasPoster, hasFanart, hasMetadataDir);

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
