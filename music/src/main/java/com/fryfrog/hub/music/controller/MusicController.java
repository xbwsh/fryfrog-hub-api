package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.ScrapeProgress;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.service.MusicMetadataService;
import com.fryfrog.hub.music.service.MusicScrapeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "音乐管理", description = "音乐元数据查询、扫描和刮削接口")
public class MusicController {

    private final MusicMetadataService service;
    private final MusicScrapeService scrapeService;
    private final ScrapeProgressService scrapeProgressService;

    @Value("${hub.music.root-paths:./media-library/music}")
    private String rootPathsConfig;

    private List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @GetMapping
    @Operation(summary = "获取所有曲目", description = "返回数据库中所有已索引的音乐曲目列表")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getAllTracks() {
        List<MusicTrack> tracks = service.getAllTracks();
        tracks.forEach(this::fillApiPaths);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取曲目详情", description = "根据ID获取单个曲目的详细信息，包含歌词、封面路径等")
    public ResponseEntity<ApiResponse<MusicTrack>> getTrackById(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = service.getTrackById(id);
        fillApiPaths(track);
        return ResponseEntity.ok(ApiResponse.success(track));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按标题搜索", description = "根据标题关键词模糊搜索音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        List<MusicTrack> tracks = service.searchByTitle(q);
        tracks.forEach(this::fillApiPaths);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/search/artist")
    @Operation(summary = "按艺术家搜索", description = "根据艺术家名称模糊搜索音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByArtist(
            @Parameter(description = "艺术家名称关键词") @RequestParam String q) {
        List<MusicTrack> tracks = service.searchByArtist(q);
        tracks.forEach(this::fillApiPaths);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回所有已收藏的音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getFavorites() {
        List<MusicTrack> tracks = service.getFavorites();
        tracks.forEach(this::fillApiPaths);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置曲目的收藏状态")
    public ResponseEntity<ApiResponse<MusicTrack>> setFavorite(
            @Parameter(description = "曲目ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        return ResponseEntity.ok(ApiResponse.success(service.setFavorite(id, status)));
    }

    @PostMapping("/rescan")
    @Operation(summary = "一键刷新音乐库", description = "扫描所有根路径 → 整理文件夹 → 自动刮削歌词/封面")
    public ResponseEntity<ApiResponse<String>> rescan() {
        for (String rootPath : getRootPaths()) {
            try {
                service.scanDirectory(rootPath);
            } catch (Exception e) {
                log.error("Failed to scan music directory {}: {}", rootPath, e.getMessage());
            }
        }
        return ResponseEntity.ok(ApiResponse.success("Rescan started: scan → scrape"));
    }

    @GetMapping("/{id:\\d+}/lyrics")
    @Operation(summary = "获取歌词", description = "读取同目录下的.lrc歌词文件")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回歌词内容"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "曲目不存在或无歌词文件")
    })
    public ResponseEntity<ApiResponse<String>> getLyrics(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = service.getTrackById(id);
        Path audioPath = Paths.get(track.getFilePath());
        Path lyricsPath = audioPath.getParent().resolve(
                audioPath.getFileName().toString().replaceAll("\\.[^.]+$", ".lrc"));

        if (!Files.exists(lyricsPath)) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }

        try {
            String lyrics = Files.readString(lyricsPath);
            return ResponseEntity.ok(ApiResponse.success(lyrics));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
    }

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面图片", description = "返回曲目内嵌的封面图片（JPEG格式）")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回图片文件"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "曲目不存在或无封面")
    })
    public ResponseEntity<Resource> getCoverArt(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = service.getTrackById(id);
        File coverFile = findCoverFile(track);
        if (coverFile == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = resolveImageMediaType(coverFile.getName());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new FileSystemResource(coverFile));
    }

    private File findCoverFile(MusicTrack track) {
        if (track.getCoverArtPath() != null) {
            File coverFile = new File(track.getCoverArtPath());
            if (coverFile.exists()) {
                return coverFile;
            }
        }
        Path audioPath = Paths.get(track.getFilePath());
        Path parentDir = audioPath.getParent();
        if (parentDir == null) {
            return null;
        }
        for (String ext : List.of("cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "cover.gif")) {
            File fallback = parentDir.resolve(ext).toFile();
            if (fallback.exists()) {
                return fallback;
            }
        }
        return null;
    }

    @GetMapping("/{id:\\d+}/stream")
    @Operation(summary = "播放音乐", description = "流式返回音频文件，支持Range请求实现断点续播")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回完整音频"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "206", description = "返回部分内容（Partial Content）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "曲目不存在")
    })
    public ResponseEntity<Resource> streamTrack(
            @Parameter(description = "曲目ID") @PathVariable Long id,
            @Parameter(description = "字节范围，如 bytes=0-1023") @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            HttpServletRequest request,
            HttpServletResponse response) {
        MusicTrack track = service.getTrackById(id);
        File file = new File(track.getFilePath());

        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        long fileLength = file.length();
        Resource resource = new FileSystemResource(file);

        if (range == null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileLength))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(resource);
        }

        String[] ranges = range.replace("bytes=", "").split("-");
        long start = Long.parseLong(ranges[0]);
        long end = ranges.length > 1 && !ranges[1].isEmpty()
                ? Long.parseLong(ranges[1])
                : fileLength - 1;

        long contentLength = end - start + 1;

        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try {
            java.io.InputStream inputStream = new java.io.FileInputStream(file);
            inputStream.skip(start);
            byte[] buffer = new byte[(int) contentLength];
            int bytesRead = inputStream.read(buffer, 0, (int) contentLength);
            inputStream.close();

            response.getOutputStream().write(buffer, 0, bytesRead);
            response.getOutputStream().flush();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to stream file", e);
        }

        return null;
    }

    @PostMapping("/{id:\\d+}/scrape")
    @Operation(summary = "刮削单曲元数据", description = "从在线源搜索并补充歌词、封面、专辑信息等元数据")
    public ResponseEntity<ApiResponse<MusicTrack>> scrapeTrack(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(scrapeService.scrapeTrack(id)));
    }

    @GetMapping("/scrape/status")
    @Operation(summary = "刮削状态", description = "返回待刮削曲目数量")
    public ResponseEntity<ApiResponse<Long>> scrapeStatus() {
        return ResponseEntity.ok(ApiResponse.success(scrapeService.countPendingScrape()));
    }

    @GetMapping("/scrape/progress")
    @Operation(summary = "刮削进度", description = "返回当前音乐刮削任务的进度")
    public ResponseEntity<ApiResponse<ScrapeProgress>> scrapeProgress() {
        return ResponseEntity.ok(ApiResponse.success(scrapeProgressService.getProgress("music")));
    }

    private MediaType resolveImageMediaType(String fileName) {
        if (fileName == null) {
            return MediaType.IMAGE_JPEG;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        return MediaType.IMAGE_JPEG;
    }

    private void fillApiPaths(MusicTrack track) {
        String basePath = "/api/v1/music/" + track.getId();
        track.setCoverApiPath(basePath + "/cover");
        track.setArtistImageApiPath(basePath + "/artist-image");
        track.setStreamApiPath(basePath + "/stream");
    }

    @GetMapping("/{id:\\d+}/artist-image")
    @Operation(summary = "获取歌手图片", description = "返回歌手的图片")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回图片文件"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "曲目不存在或无歌手图片")
    })
    public ResponseEntity<Resource> getArtistImage(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = service.getTrackById(id);
        if (track.getArtistImage() == null || track.getArtistImage().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        File imageFile = new File(track.getArtistImage());
        if (!imageFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = resolveImageMediaType(imageFile.getName());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new FileSystemResource(imageFile));
    }
}