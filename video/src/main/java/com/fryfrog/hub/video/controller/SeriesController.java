package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.video.dto.SeriesDTO;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.VideoService;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/video/series")
@RequiredArgsConstructor
@Tag(name = "视频系列管理", description = "视频系列/剧集分组接口")
public class SeriesController {

    private final SeriesService seriesService;
    private final VideoService videoService;
    private final NfoService nfoService;

    @GetMapping
    @Operation(summary = "获取所有系列", description = "返回所有视频系列列表（含独立电影）")
    public ResponseEntity<ApiResponse<List<SeriesDTO>>> getAllSeries() {
        List<SeriesDTO> dtos = new ArrayList<>();

        // 系列剧集
        for (VideoSeries series : seriesService.getAllSeries()) {
            List<VideoDTO> episodes = series.getVideos().stream()
                    .map(this::toVideoDTO)
                    .collect(Collectors.toList());
            dtos.add(SeriesDTO.fromEntity(series, episodes));
        }

        // 独立视频（不属于任何系列的，如电影）
        List<Video> standaloneVideos = videoService.getAllVideos().stream()
                .filter(v -> v.getSeries() == null)
                .sorted(Comparator.comparing(Video::getTitle))
                .toList();
        for (Video video : standaloneVideos) {
            dtos.add(SeriesDTO.fromStandaloneVideo(video, toVideoDTO(video)));
        }

        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取系列详情", description = "根据ID获取系列详情，包含所有剧集")
    public ResponseEntity<ApiResponse<SeriesDTO>> getSeriesById(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        VideoSeries series = seriesService.getSeriesById(id)
                .orElseThrow(() -> new RuntimeException("Series not found: " + id));
        List<VideoDTO> episodes = series.getVideos().stream()
                .map(video -> toVideoDTO(video))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(SeriesDTO.fromEntity(series, episodes)));
    }

    @GetMapping("/{id}/poster")
    @Operation(summary = "获取系列海报", description = "返回系列的竖屏海报图片")
    public ResponseEntity<Resource> getSeriesPoster(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        VideoSeries series = seriesService.getSeriesById(id).orElse(null);
        if (series == null || series.getPosterUrl() == null) {
            return generatePlaceholder(series != null ? series.getTitle() : "Unknown", 300, 450);
        }

        try {
            java.net.URL url = new java.net.URL(series.getPosterUrl());
            byte[] imageBytes = url.openStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(imageBytes));
        } catch (Exception e) {
            return generatePlaceholder(series.getTitle(), 300, 450);
        }
    }

    @GetMapping("/{id}/fanart")
    @Operation(summary = "获取系列背景图", description = "返回系列的横屏背景图片")
    public ResponseEntity<Resource> getSeriesFanart(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        VideoSeries series = seriesService.getSeriesById(id).orElse(null);
        if (series == null || series.getBackdropUrl() == null) {
            return generatePlaceholder(series != null ? series.getTitle() : "Unknown", 1920, 1080);
        }

        try {
            java.net.URL url = new java.net.URL(series.getBackdropUrl());
            byte[] imageBytes = url.openStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(imageBytes));
        } catch (Exception e) {
            return generatePlaceholder(series.getTitle(), 1920, 1080);
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

    private VideoDTO toVideoDTO(Video video) {
        java.nio.file.Path posterPath = nfoService.getPosterPath(video);
        java.nio.file.Path fanartPath = nfoService.getFanartPath(video);
        java.nio.file.Path nfoPath = nfoService.getNfoPath(video);
        java.nio.file.Path metadataDir = nfoService.getMetadataDir(video);

        return VideoDTO.fromEntity(
                video,
                Files.exists(nfoPath) ? nfoPath.toString() : null,
                Files.exists(posterPath) ? posterPath.toString() : null,
                Files.exists(fanartPath) ? fanartPath.toString() : null,
                Files.exists(metadataDir) ? metadataDir.toString() : null
        );
    }
}
