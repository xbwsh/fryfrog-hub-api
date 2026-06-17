package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.service.MusicMetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
@Tag(name = "音乐管理", description = "音乐元数据查询、扫描和刮削接口")
public class MusicController {

    private final MusicMetadataService service;

    @Value("${hub.music.root-path}")
    private String rootPath;

    @GetMapping
    @Operation(summary = "获取所有曲目", description = "返回数据库中所有已索引的音乐曲目列表")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getAllTracks() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllTracks()));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取曲目详情", description = "根据ID获取单个曲目的详细信息，包含歌词、封面路径等")
    public ResponseEntity<ApiResponse<MusicTrack>> getTrackById(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getTrackById(id)));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按标题搜索", description = "根据标题关键词模糊搜索音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByTitle(q)));
    }

    @GetMapping("/search/artist")
    @Operation(summary = "按艺术家搜索", description = "根据艺术家名称模糊搜索音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByArtist(
            @Parameter(description = "艺术家名称关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByArtist(q)));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回所有已收藏的音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getFavorites() {
        return ResponseEntity.ok(ApiResponse.success(service.getFavorites()));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置曲目的收藏状态")
    public ResponseEntity<ApiResponse<MusicTrack>> setFavorite(
            @Parameter(description = "曲目ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        return ResponseEntity.ok(ApiResponse.success(service.setFavorite(id, status)));
    }

    @PostMapping("/scan")
    @Operation(summary = "扫描媒体目录", description = "递归扫描指定目录，提取所有支持格式的音频文件元数据并入库")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "扫描完成"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "路径不在允许的根目录内")
    })
    public ResponseEntity<ApiResponse<String>> scanDirectory(
            @Parameter(description = "要扫描的目录路径（必须在配置的根目录内）") @RequestParam String path) {
        validatePath(path);
        service.scanDirectory(path);
        return ResponseEntity.ok(ApiResponse.success("Scan completed", path));
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
        if (track.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }
        File coverFile = new File(track.getCoverArtPath());
        if (!coverFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(coverFile));
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

    private void validatePath(String path) {
        Path requestedPath = Paths.get(path).toAbsolutePath().normalize();
        Path allowedRoot = Paths.get(rootPath).toAbsolutePath().normalize();
        if (!requestedPath.startsWith(allowedRoot)) {
            throw new IllegalArgumentException("Path is outside allowed root: " + rootPath);
        }
    }
}