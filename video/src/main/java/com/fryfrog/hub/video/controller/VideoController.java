package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.ScrapeProgress;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
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
import com.fryfrog.hub.video.service.MediaInfoService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SubtitleService;
import com.fryfrog.hub.video.service.TmdbService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    private final MediaInfoService mediaInfoService;
    private final SubtitleService subtitleService;
    private final VideoActorRepository actorRepository;
    private final ScrapeProgressService scrapeProgressService;
    private final MediaLibraryService mediaLibraryService;

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
    @Operation(summary = "一键刷新视频库", description = "扫描所有资源库 → 整理文件夹 → 自动刮削未绑定视频")
    public ResponseEntity<ApiResponse<String>> rescan() {
        List<MediaLibrary> libraries = mediaLibraryService.getEnabledLibraries();
        for (MediaLibrary library : libraries) {
            try {
                service.scanDirectory(library.getPath(), library.getId());
            } catch (Exception e) {
                log.error("Failed to scan library {} ({}): {}", library.getName(), library.getPath(), e.getMessage());
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

    @GetMapping("/actor/{actorId:\\d+}/image")
    @Operation(summary = "获取演员头像", description = "返回指定演员的头像图片")
    public ResponseEntity<Resource> getActorImage(
            @Parameter(description = "演员ID") @PathVariable Long actorId) {
        VideoActor actor = actorRepository.findById(actorId).orElse(null);
        if (actor == null || actor.getImagePath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path imagePath = Paths.get(actor.getImagePath());
        if (!Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(imagePath.toFile()));
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
    @Operation(summary = "解绑TMDB元数据", description = "解绑该视频所属系列的所有视频（同tmdbId）的TMDB元数据")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unbindTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        if (video.getTmdbId() == null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("unbound", 0)));
        }
        Long tmdbId = video.getTmdbId();
        int count = service.unbindByTmdbId(tmdbId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "tmdbId", tmdbId,
                "unbound", count
        )));
    }

    @PostMapping("/{id:\\d+}/tmdb/refresh")
    @Operation(summary = "刷新TMDB元数据", description = "重新刮削该视频所属系列的所有视频（同tmdbId）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        List<Video> results = service.rescrapeVideo(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "total", results.size(),
                "videos", results.stream().map(this::toDTO).toList()
        )));
    }

    @PostMapping("/tmdb/rescrape-all")
    @Operation(summary = "全量重新刮削", description = "解绑所有视频的TMDB绑定，然后重新搜索绑定")
    public ResponseEntity<ApiResponse<String>> rescrapeAll() {
        service.rescrapeAll();
        return ResponseEntity.ok(ApiResponse.success("Rescrape started: unbind all → re-scrape"));
    }

    @PostMapping("/tmdb/rescrape-library/{libraryId}")
    @Operation(summary = "按资源库重新刮削", description = "解绑指定资源库中所有视频的TMDB绑定，然后根据资源库类型重新搜索绑定")
    public ResponseEntity<ApiResponse<String>> rescrapeByLibrary(
            @Parameter(description = "资源库ID") @PathVariable Long libraryId) {
        service.rescrapeByLibrary(libraryId);
        return ResponseEntity.ok(ApiResponse.success("Rescrape started for library " + libraryId));
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
    @Operation(summary = "保存观看进度", description = "保存视频的播放位置和总时长")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> saveProgress(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody WatchProgressRequest request) {
        WatchProgress progress = watchProgressService.saveProgress(id, request.getPosition(), request.getDuration());
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/watched")
    @Operation(summary = "标记已观看", description = "手动标记视频为已观看状态")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> markAsWatched(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        WatchProgress progress = watchProgressService.markAsWatched(id);
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @DeleteMapping("/{id:\\d+}/watched")
    @Operation(summary = "取消已观看", description = "取消视频的已观看状态")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> markAsUnwatched(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        WatchProgress progress = watchProgressService.markAsUnwatched(id);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @GetMapping("/{id:\\d+}/stream")
    @Operation(summary = "视频流播放", description = "支持 Range 请求，用于本地播放器直接播放")
    public ResponseEntity<Resource> streamVideo(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        Video video = service.getVideoById(id);
        File videoFile = new File(video.getFilePath());

        if (!videoFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        String contentType = getContentType(video.getFileName());
        long fileLength = videoFile.length();

        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.set("Accept-Ranges", "bytes");
            headers.setContentLength(fileLength);
            return ResponseEntity.ok().headers(headers).body(new FileSystemResource(videoFile));
        }

        String[] ranges = rangeHeader.substring(6).split("-");
        long start;
        long end;

        try {
            start = Long.parseLong(ranges[0]);
            end = (ranges.length > 1 && !ranges[1].isEmpty()) ? Long.parseLong(ranges[1]) : fileLength - 1;
        } catch (NumberFormatException e) {
            start = 0;
            end = fileLength - 1;
        }

        start = Math.max(0, start);
        end = Math.min(fileLength - 1, end);
        long contentLength = end - start + 1;

        Resource resource = new RangeResource(videoFile, start, contentLength);
        ResourceRegion region = new ResourceRegion(resource, 0, contentLength);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set("Accept-Ranges", "bytes");
        headers.set("Content-Range", String.format("bytes %d-%d/%d", start, end, fileLength));
        headers.setContentLength(contentLength);

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(headers)
                .body(resource);
    }

    @GetMapping("/{id:\\d+}/stream/info")
    @Operation(summary = "获取播放链接", description = "返回视频流 URL，用于本地播放器")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStreamInfo(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Map<String, Object> info = Map.of(
                "videoId", id,
                "fileName", video.getFileName(),
                "streamUrl", "/api/v1/video/" + id + "/stream"
        );
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    @GetMapping("/{id:\\d+}/media-info")
    @Operation(summary = "获取媒体技术信息", description = "使用 ffprobe 分析视频的编码、分辨率、帧率等技术信息")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMediaInfo(
            @Parameter(description = "视频ID") @PathVariable Long id) throws Exception {
        Video video = service.getVideoById(id);
        MediaInfoService.MediaInfo info = mediaInfoService.extractMediaInfo(video.getFilePath());

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("videoCodec", info.videoCodec != null ? info.videoCodec : "unknown");
        result.put("videoCodecLong", info.videoCodecLong != null ? info.videoCodecLong : "");
        result.put("audioCodec", info.audioCodec != null ? info.audioCodec : "unknown");
        result.put("audioCodecLong", info.audioCodecLong != null ? info.audioCodecLong : "");
        result.put("audioChannels", info.audioChannels);
        result.put("audioSampleRate", info.audioSampleRate != null ? info.audioSampleRate : "");
        result.put("resolution", info.resolution != null ? info.resolution : "unknown");
        result.put("frameRate", info.frameRate);
        result.put("bitrateKbps", info.bitrateKbps);
        result.put("durationSeconds", info.durationSeconds);
        result.put("durationMinutes", info.durationMinutes);
        result.put("format", info.formatName != null ? info.formatName : "unknown");

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{id:\\d+}/subtitles")
    @Operation(summary = "获取内嵌字幕列表", description = "列出视频中所有内嵌字幕轨道")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSubtitles(
            @Parameter(description = "视频ID") @PathVariable Long id) throws Exception {
        Video video = service.getVideoById(id);
        List<Map<String, Object>> subtitles = mediaInfoService.getSubtitleStreams(video.getFilePath());
        return ResponseEntity.ok(ApiResponse.success(subtitles));
    }

    @PostMapping("/{id:\\d+}/subtitles/extract")
    @Operation(summary = "提取内嵌字幕", description = "将内嵌字幕提取为独立文件")
    public ResponseEntity<ApiResponse<List<SubtitleService.SubtitleFile>>> extractSubtitles(
            @Parameter(description = "视频ID") @PathVariable Long id) throws Exception {
        Video video = service.getVideoById(id);
        String videoDir = new File(video.getFilePath()).getParent();
        String metadataDir = nfoService.getMetadataDir(video).toString();

        List<SubtitleService.SubtitleFile> files = subtitleService.extractSubtitles(
                video.getFilePath(), metadataDir);

        return ResponseEntity.ok(ApiResponse.success(files));
    }

    @GetMapping("/{id:\\d+}/subtitles/files")
    @Operation(summary = "获取已提取的字幕文件", description = "列出视频目录下已提取的字幕文件")
    public ResponseEntity<ApiResponse<List<String>>> getSubtitleFiles(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        String baseName = nfoService.getBaseName(video.getFileName());
        String metadataDir = nfoService.getMetadataDir(video).toString();

        List<String> files = subtitleService.getAvailableSubtitles(metadataDir, baseName);
        return ResponseEntity.ok(ApiResponse.success(files));
    }

    @GetMapping("/{id:\\d+}/subtitles/{fileName}")
    @Operation(summary = "获取字幕内容", description = "返回字幕文件内容，.ass/.srt 自动转为 .vtt 格式")
    public ResponseEntity<Resource> getSubtitleContent(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Parameter(description = "字幕文件名") @PathVariable String fileName) {
        Video video = service.getVideoById(id);
        String metadataDir = nfoService.getMetadataDir(video).toString();
        Path subtitlePath = Paths.get(metadataDir, fileName);

        if (!Files.exists(subtitlePath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path vttPath = subtitleService.convertToVtt(subtitlePath);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/vtt; charset=utf-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + vttPath.getFileName() + "\"")
                    .body(new FileSystemResource(vttPath.toFile()));
        } catch (Exception e) {
            log.warn("Failed to convert subtitle {}: {}", fileName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private String getContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".m4v")) return "video/x-m4v";
        return "application/octet-stream";
    }

    private static class RangeResource extends org.springframework.core.io.AbstractResource {
        private final File file;
        private final long start;
        private final long length;

        public RangeResource(File file, long start, long length) {
            this.file = file;
            this.start = start;
            this.length = length;
        }

        @Override
        public String getDescription() {
            return "Range resource [" + file.getName() + " bytes " + start + "-" + (start + length - 1) + "]";
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(start);
            return new BoundedInputStream(raf, length);
        }
    }

    private static class BoundedInputStream extends InputStream {
        private final RandomAccessFile raf;
        private final long maxLength;
        private long position = 0;

        public BoundedInputStream(RandomAccessFile raf, long maxLength) {
            this.raf = raf;
            this.maxLength = maxLength;
        }

        @Override
        public int read() throws IOException {
            if (position >= maxLength) return -1;
            int b = raf.read();
            if (b >= 0) position++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= maxLength) return -1;
            int toRead = (int) Math.min(len, maxLength - position);
            int read = raf.read(b, off, toRead);
            if (read > 0) position += read;
            return read;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
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
