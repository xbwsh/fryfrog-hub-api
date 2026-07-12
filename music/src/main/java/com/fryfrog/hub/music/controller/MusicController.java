package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.model.Playlist;
import com.fryfrog.hub.music.model.PlaylistTrack;
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
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "音乐管理", description = "音乐元数据查询、扫描和整理接口")
public class MusicController {

    private final MusicMetadataService service;
    private final MusicScrapeService scrapeService;

    @Value("${hub.music.root-paths:}")
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
        tracks.forEach(this::checkExternalLyrics);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取曲目详情", description = "根据ID获取单个曲目的详细信息")
    public ResponseEntity<ApiResponse<MusicTrack>> getTrackById(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = service.getTrackById(id);
        checkExternalLyrics(track);
        return ResponseEntity.ok(ApiResponse.success(track));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按标题搜索", description = "根据标题关键词模糊搜索音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        List<MusicTrack> tracks = service.searchByTitle(q);
        tracks.forEach(this::checkExternalLyrics);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/search/artist")
    @Operation(summary = "按艺术家搜索", description = "根据艺术家名称模糊搜索音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByArtist(
            @Parameter(description = "艺术家名称关键词") @RequestParam String q) {
        List<MusicTrack> tracks = service.searchByArtist(q);
        tracks.forEach(this::checkExternalLyrics);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回所有已收藏的音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getFavorites() {
        List<MusicTrack> tracks = service.getFavorites();
        tracks.forEach(this::checkExternalLyrics);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "切换收藏状态", description = "切换曲目的收藏状态（支持body传status或自动切换）")
    public ResponseEntity<ApiResponse<MusicTrack>> setFavorite(
            @Parameter(description = "曲目ID") @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        MusicTrack track = service.getTrackById(id);
        boolean newStatus;
        if (body != null && body.containsKey("status")) {
            newStatus = Boolean.TRUE.equals(body.get("status"));
        } else {
            newStatus = !Boolean.TRUE.equals(track.getFavorite());
        }
        return ResponseEntity.ok(ApiResponse.success(service.setFavorite(id, newStatus)));
    }

    @GetMapping("/recently-played")
    @Operation(summary = "最近播放", description = "返回最近播放过的音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getRecentlyPlayed() {
        List<MusicTrack> tracks = service.getRecentlyPlayed();
        tracks.forEach(this::checkExternalLyrics);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/most-played")
    @Operation(summary = "最常播放", description = "返回播放次数最多的音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getMostPlayed() {
        List<MusicTrack> tracks = service.getMostPlayed();
        tracks.forEach(this::checkExternalLyrics);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @GetMapping("/recently-added")
    @Operation(summary = "最近添加", description = "返回最近添加的音乐曲目")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getRecentlyAdded() {
        List<MusicTrack> tracks = service.getRecentlyAdded();
        tracks.forEach(this::checkExternalLyrics);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }

    @PostMapping("/{id:\\d+}/play")
    @Operation(summary = "记录播放", description = "记录曲目播放次数和最后播放时间")
    public ResponseEntity<ApiResponse<MusicTrack>> recordPlay(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = service.recordPlay(id);
        checkExternalLyrics(track);
        return ResponseEntity.ok(ApiResponse.success(track));
    }

    @GetMapping("/recommendations")
    @Operation(summary = "推荐歌单", description = "根据听歌习惯生成多个推荐分类")
    public ResponseEntity<ApiResponse<Map<String, List<MusicTrack>>>> getRecommendations() {
        Map<String, List<MusicTrack>> recommendations = service.getRecommendations();
        recommendations.values().forEach(list -> list.forEach(this::checkExternalLyrics));
        return ResponseEntity.ok(ApiResponse.success(recommendations));
    }

    @GetMapping("/playlists")
    @Operation(summary = "获取所有播放列表", description = "返回所有播放列表")
    public ResponseEntity<ApiResponse<List<Playlist>>> getAllPlaylists() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllPlaylists()));
    }

    @PostMapping("/playlists")
    @Operation(summary = "创建播放列表", description = "创建新的播放列表")
    public ResponseEntity<ApiResponse<Playlist>> createPlaylist(
            @RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "新建播放列表");
        String description = body.getOrDefault("description", "");
        return ResponseEntity.ok(ApiResponse.success(service.createPlaylist(name, description)));
    }

    @PutMapping("/playlists/{id:\\d+}")
    @Operation(summary = "更新播放列表", description = "更新播放列表名称和描述")
    public ResponseEntity<ApiResponse<Playlist>> updatePlaylist(
            @Parameter(description = "播放列表ID") @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(
                service.updatePlaylist(id, body.get("name"), body.get("description"))));
    }

    @DeleteMapping("/playlists/{id:\\d+}")
    @Operation(summary = "删除播放列表", description = "删除播放列表及其所有曲目")
    public ResponseEntity<ApiResponse<Void>> deletePlaylist(
            @Parameter(description = "播放列表ID") @PathVariable Long id) {
        service.deletePlaylist(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/playlists/{id:\\d+}/tracks")
    @Operation(summary = "获取播放列表曲目", description = "返回播放列表中的所有曲目")
    public ResponseEntity<ApiResponse<List<PlaylistTrack>>> getPlaylistTracks(
            @Parameter(description = "播放列表ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getPlaylistTracks(id)));
    }

    @PostMapping("/playlists/{id:\\d+}/tracks")
    @Operation(summary = "添加曲目到播放列表", description = "将曲目添加到播放列表")
    public ResponseEntity<ApiResponse<PlaylistTrack>> addTrackToPlaylist(
            @Parameter(description = "播放列表ID") @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Long trackId = body.get("trackId");
        return ResponseEntity.ok(ApiResponse.success(service.addTrackToPlaylist(id, trackId)));
    }

    @DeleteMapping("/playlists/{playlistId:\\d+}/tracks/{trackId:\\d+}")
    @Operation(summary = "从播放列表移除曲目", description = "从播放列表中移除指定曲目")
    public ResponseEntity<ApiResponse<Void>> removeTrackFromPlaylist(
            @Parameter(description = "播放列表ID") @PathVariable Long playlistId,
            @Parameter(description = "曲目ID") @PathVariable Long trackId) {
        service.removeTrackFromPlaylist(playlistId, trackId);
        return ResponseEntity.ok(ApiResponse.success(null));
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
    @Operation(summary = "获取封面图片", description = "返回曲目内嵌的封面图片，无封面时返回标题占位图")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回图片文件"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "曲目不存在")
    })
    public ResponseEntity<Resource> getCoverArt(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = service.getTrackById(id);
        File coverFile = findCoverFile(track);
        if (coverFile != null) {
            MediaType mediaType = resolveImageMediaType(coverFile.getName());
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(new FileSystemResource(coverFile));
        }
        try {
            byte[] placeholder = com.fryfrog.hub.common.util.PlaceholderImageGenerator.generate(track.getTitle(), 300, 300);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new org.springframework.core.io.ByteArrayResource(placeholder));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
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
        MediaType contentType = resolveAudioMediaType(file.getName());

        if (range == null) {
            return ResponseEntity.ok()
                    .contentType(contentType)
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
        response.setContentType(contentType.toString());

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

    @GetMapping("/{id:\\d+}/artist/image")
    @Operation(summary = "获取歌手图片", description = "返回歌手的图片（歌手文件夹下的artist.jpg）")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回图片文件"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "曲目不存在或无歌手图片")
    })
    public ResponseEntity<Resource> getArtistImage(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        String imagePath = service.getArtistImagePath(id);
        if (imagePath == null) {
            return ResponseEntity.notFound().build();
        }
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = resolveImageMediaType(imageFile.getName());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new FileSystemResource(imageFile));
    }

    @PostMapping("/{id:\\d+}/artist/image/scrape")
    @Operation(summary = "刮削歌手图片", description = "从在线源获取歌手图片并保存到歌手文件夹")
    public ResponseEntity<ApiResponse<String>> scrapeArtistImage(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        String imagePath = service.scrapeArtistImage(id);
        if (imagePath == null) {
            return ResponseEntity.ok(ApiResponse.error("未找到歌手图片"));
        }
        return ResponseEntity.ok(ApiResponse.success(imagePath));
    }

    @PostMapping("/{id:\\d+}/cover/scrape")
    @Operation(summary = "刮削封面", description = "从在线源获取专辑封面并保存到本地")
    public ResponseEntity<ApiResponse<MusicTrack>> scrapeCover(
            @Parameter(description = "曲目ID") @PathVariable Long id) {
        MusicTrack track = scrapeService.scrapeTrack(id);
        return ResponseEntity.ok(ApiResponse.success(track));
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

    private MediaType resolveAudioMediaType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (lower.endsWith(".flac")) return MediaType.parseMediaType("audio/flac");
        if (lower.endsWith(".ogg")) return MediaType.parseMediaType("audio/ogg");
        if (lower.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (lower.endsWith(".aac")) return MediaType.parseMediaType("audio/aac");
        if (lower.endsWith(".m4a")) return MediaType.parseMediaType("audio/mp4");
        if (lower.endsWith(".wma")) return MediaType.parseMediaType("audio/x-ms-wma");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private void checkExternalLyrics(MusicTrack track) {
        if (track.getFilePath() != null) {
            Path audioPath = Path.of(track.getFilePath());
            Path parent = audioPath.getParent();
            if (parent != null) {
                String baseName = audioPath.getFileName().toString().replaceAll("\\.[^.]+$", "");
                Path lrcPath = parent.resolve(baseName + ".lrc");
                track.setHasExternalLyrics(Files.exists(lrcPath));
            }
        }
    }
}
