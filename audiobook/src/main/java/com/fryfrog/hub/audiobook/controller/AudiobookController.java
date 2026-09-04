package com.fryfrog.hub.audiobook.controller;

import com.fryfrog.hub.audiobook.dto.AudiobookDetailDTO;
import com.fryfrog.hub.audiobook.dto.AudiobookListDTO;
import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookChapter;
import com.fryfrog.hub.audiobook.model.AudiobookProgress;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookChapterRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.audiobook.service.AudiobookOrganizeService;
import com.fryfrog.hub.audiobook.service.AudiobookProgressService;
import com.fryfrog.hub.audiobook.service.AudiobookScrapeService;
import com.fryfrog.hub.audiobook.service.AudiobookScanService;
import com.fryfrog.hub.audiobook.service.AudiobookStreamService;
import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import com.fryfrog.hub.common.exception.ForbiddenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/v1/audiobooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "有声书管理", description = "有声书扫描、播放、进度接口")
public class AudiobookController {

    private final AudiobookRepository bookRepository;
    private final AudiobookTrackRepository trackRepository;
    private final AudiobookChapterRepository chapterRepository;
    private final AudiobookScanService scanService;
    private final AudiobookOrganizeService organizeService;
    private final AudiobookScrapeService scrapeService;
    private final AudiobookStreamService streamService;
    private final AudiobookProgressService progressService;
    private final MediaLibraryService mediaLibraryService;
    private final UserService userService;

    private void requireAdmin(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        if (!userService.isAdmin(userId)) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    /** 库 ID 可见性过滤：受限用户仅授权库。 */
    private List<Long> allowableIds() {
        return mediaLibraryService.getAllowableLibraryIds();
    }

    private Audiobook requireVisibleBook(Long id) {
        Audiobook book = bookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audiobook", "id", id));
        if (book.getLibraryId() != null && !allowableIds().contains(book.getLibraryId())) {
            throw new ResourceNotFoundException("Audiobook", "id", id);
        }
        return book;
    }

    // ── 扫描 ──

    @PostMapping("/scan")
    @Operation(summary = "扫描有声书资源库", description = "扫描指定 AUDIOBOOK 资源库（异步执行），不传 libraryId 时扫描全部")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scan(
            @Parameter(description = "资源库ID，可选") @RequestParam(required = false) Long libraryId,
            HttpServletRequest request) {
        requireAdmin(request);
        if (libraryId != null && !mediaLibraryService.isVisibleToCurrentUser(libraryId)) {
            throw new ResourceNotFoundException("MediaLibrary", "id", libraryId);
        }
        List<MediaLibrary> libraries = libraryId != null
                ? List.of(mediaLibraryService.getLibraryById(libraryId))
                : mediaLibraryService.getVisibleLibraries().stream()
                        .filter(MediaLibrary::isAudiobookType).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("libraryCount", libraries.size());
        Thread.startVirtualThread(() -> {
            for (MediaLibrary lib : libraries) {
                try {
                    scanService.scanAndSave(lib.getPath(), lib.getId());
                } catch (Exception e) {
                    log.warn("[AudiobookScan] Failed library '{}': {}", lib.getName(), e.getMessage());
                }
            }
        });
        return ResponseEntity.ok(ApiResponse.success("扫描任务已启动", result));
    }

    // ── 整理 ──

    @PostMapping("/organize")
    @Operation(summary = "整理有声书文件", description = "将库根下的旧扁平格式（剑来001.mp3）整理为 剑来/剑来第一季/001.mp3 结构并迁移数据库记录；dryRun=true 仅预览不移动")
    public ResponseEntity<ApiResponse<Map<String, Object>>> organize(
            @Parameter(description = "资源库ID") @RequestParam Long libraryId,
            @Parameter(description = "是否仅预览") @RequestParam(defaultValue = "true") boolean dryRun,
            HttpServletRequest request) {
        requireAdmin(request);
        if (!mediaLibraryService.isVisibleToCurrentUser(libraryId)) {
            throw new ResourceNotFoundException("MediaLibrary", "id", libraryId);
        }
        return ResponseEntity.ok(ApiResponse.success(organizeService.organize(libraryId, dryRun)));
    }

    // ── 列表 / 详情 ──

    @GetMapping
    @Operation(summary = "有声书列表", description = "返回当前用户可见库中的有声书，支持分页与关键词搜索")
    public ResponseEntity<ApiResponse<PageResponse<AudiobookListDTO>>> list(
            @Parameter(description = "搜索关键词（书名模糊）") @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        List<Long> allowed = allowableIds();
        if (allowed.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(PageResponse.of(List.of(), page, size, 0)));
        }
        Page<Audiobook> result;
        if (q != null && !q.isBlank()) {
            result = bookRepository.findByLibraryIdInAndTitleContainingIgnoreCase(
                    allowed, q, PageRequest.of(page, size, Sort.by("title").ascending()));
        } else {
            result = bookRepository.findByLibraryIdIn(allowed,
                    PageRequest.of(page, size, Sort.by("title").ascending()));
        }
        Map<Long, AudiobookProgress> progressMap = progressService.getProgressByBookIds(
                userId, result.getContent().stream().map(Audiobook::getId).toList());
        List<AudiobookListDTO> dtos = result.getContent().stream()
                .map(b -> AudiobookListDTO.from(b, progressMap.get(b.getId())))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "有声书详情", description = "返回元数据、音轨/章节与当前用户进度。SINGLE 模式 chapters 为文件内嵌章节；MULTI 模式 tracks 即章节")
    public ResponseEntity<ApiResponse<AudiobookDetailDTO>> detail(
            @Parameter(description = "有声书ID") @PathVariable Long id,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        Audiobook book = requireVisibleBook(id);

        List<AudiobookTrack> tracks = trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(id);
        List<AudiobookDetailDTO.TrackDTO> trackDTOs = tracks.stream()
                .map(AudiobookDetailDTO.TrackDTO::from).toList();

        // 章节统一为全局时间轴：SINGLE 用内嵌章节；MULTI 由音轨累计
        List<AudiobookDetailDTO.ChapterDTO> chapters;
        if (Audiobook.TYPE_SINGLE.equals(book.getPlayType())) {
            List<AudiobookChapter> rows = chapterRepository.findByAudiobook_IdOrderByChapterIndexAsc(id);
            chapters = rows.stream()
                    .map(c -> AudiobookDetailDTO.ChapterDTO.builder()
                            .chapterIndex(c.getChapterIndex())
                            .title(c.getTitle())
                            .startSeconds(c.getStartSeconds())
                            .endSeconds(c.getEndSeconds())
                            .trackIndex(0)
                            .startInTrack(c.getStartSeconds())
                            .build())
                    .toList();
            if (chapters.isEmpty() && !tracks.isEmpty()) {
                Double dur = tracks.get(0).getDurationSeconds();
                chapters = List.of(AudiobookDetailDTO.ChapterDTO.builder()
                        .chapterIndex(0).title(book.getTitle())
                        .startSeconds(0d).endSeconds(dur)
                        .trackIndex(0).startInTrack(0d).build());
            }
        } else {
            chapters = buildGlobalChapters(tracks);
        }

        AudiobookProgress progress = progressService.getProgress(userId, id);
        double globalSeconds = globalSecondsOf(progress, tracks);
        return ResponseEntity.ok(ApiResponse.success(AudiobookDetailDTO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .narrator(book.getNarrator())
                .overview(book.getOverview())
                .metadataSource(book.getMetadataSource())
                .series(book.getSeries())
                .seriesPart(book.getSeriesPart())
                .playType(book.getPlayType())
                .coverUrl(book.getCoverUrl())
                .totalDurationSeconds(book.getTotalDurationSeconds())
                .trackCount(book.getTrackCount())
                .tracks(trackDTOs)
                .chapters(chapters)
                .progress(progress != null
                        ? AudiobookDetailDTO.ProgressDTO.from(progress, book, globalSeconds) : null)
                .build()));
    }

    // ── 封面 / 流 ──

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面", description = "返回有声书封面图片")
    public ResponseEntity<FileSystemResource> cover(@PathVariable Long id) {
        Audiobook book = requireVisibleBook(id);
        if (book.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(book.getCoverArtPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(path.toFile()));
    }

    @GetMapping("/tracks/{trackId:\\d+}/stream")
    @Operation(summary = "音轨流播放", description = "支持 Range 请求断点续播")
    public void streamTrack(
            @Parameter(description = "音轨ID") @PathVariable Long trackId,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response) throws Exception {
        AudiobookTrack track = trackRepository.findWithAudiobookById(trackId)
                .orElseThrow(() -> new ResourceNotFoundException("AudiobookTrack", "id", trackId));
        requireVisibleBook(track.getAudiobook().getId());
        File file = new File(track.getFilePath());
        streamService.stream(response, file, track.getFormat(), rangeHeader, null);
    }

    // ── 进度 ──

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "保存收听进度", description = "body: {trackIndex, positionSeconds}；最后一轨播放超 95% 自动标记听完")
    public ResponseEntity<ApiResponse<AudiobookDetailDTO.ProgressDTO>> saveProgress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleBook(id);
        Integer trackIndex = body.get("trackIndex") instanceof Number n ? n.intValue() : null;
        Double position = body.get("positionSeconds") instanceof Number n ? n.doubleValue() : null;
        AudiobookProgress progress = progressService.updatePosition(userId, id, trackIndex, position);

        List<AudiobookTrack> tracks = trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(id);
        double globalSeconds = globalSecondsOf(progress, tracks);
        return ResponseEntity.ok(ApiResponse.success(
                AudiobookDetailDTO.ProgressDTO.from(progress, bookRepository.findById(id).orElse(null), globalSeconds)));
    }

    @PutMapping("/{id:\\d+}/completed")
    @Operation(summary = "设置听完状态", description = "body: {completed}")
    public ResponseEntity<ApiResponse<Void>> setCompleted(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleBook(id);
        boolean completed = Boolean.TRUE.equals(body.get("completed"));
        progressService.setCompleted(userId, id, completed);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "清除收听进度")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(@PathVariable Long id, HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleBook(id);
        progressService.deleteProgress(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 作者聚合 ──

    @GetMapping("/authors")
    @Operation(summary = "作者列表", description = "按可见库聚合作者及著作数，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<Map<String, Object>>>> authors(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Object[]> rows = bookRepository.countByAuthor(allowableIds(),
                PageRequest.of(page, size));
        List<Map<String, Object>> content = rows.getContent().stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("author", row[0]);
                    item.put("bookCount", ((Number) row[1]).longValue());
                    return item;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(content, page, size, rows.getTotalElements())));
    }

    // ── 元数据编辑 / 刮削 ──

    @PutMapping("/{id:\\d+}/metadata")
    @Operation(summary = "编辑有声书元数据", description = "手动修正书名/作者/朗读者/简介/系列（只更新传入的非空字段），同时清除刮削绑定")
    public ResponseEntity<ApiResponse<AudiobookDetailDTO>> updateMetadata(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        requireAdmin(req);
        Audiobook book = requireVisibleBook(id);
        boolean updated = false;

        if (body.get("title") instanceof String s && !s.isBlank()) { book.setTitle(s); updated = true; }
        if (body.get("author") instanceof String s && !s.isBlank()) { book.setAuthor(s); updated = true; }
        if (body.get("narrator") instanceof String s) { book.setNarrator(s.isBlank() ? null : s); updated = true; }
        if (body.get("overview") instanceof String s) { book.setOverview(s.isBlank() ? null : s); updated = true; }
        if (body.get("series") instanceof String s) { book.setSeries(s.isBlank() ? null : s); updated = true; }
        if (body.get("seriesPart") instanceof Number n) { book.setSeriesPart(n.intValue()); updated = true; }

        if (updated) {
            book.setMetadataSource("manual");
            book.setSourceId(null);
            bookRepository.save(book);
            log.info("[Audiobook] Metadata manually updated for book {}", id);
        }
        return detail(id, req);
    }

    @GetMapping("/scrape/providers")
    @Operation(summary = "刮削数据源列表")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> scrapeProviders(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(scrapeService.listProviders()));
    }

    @GetMapping("/scrape/search")
    @Operation(summary = "搜索刮削候选", description = "q=关键词；source 可选（不传查全部源）")
    public ResponseEntity<ApiResponse<List<com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult>>> scrapeSearch(
            @RequestParam String q,
            @RequestParam(required = false) String source,
            HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(ApiResponse.success(scrapeService.search(q, source)));
    }

    @PostMapping("/{id:\\d+}/scrape/bind")
    @Operation(summary = "绑定刮削元数据", description = "body: {source, sourceId}；结果落库并下载封面，不改音频文件")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scrapeBind(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        requireAdmin(request);
        requireVisibleBook(id);
        String source = body.get("source");
        String sourceId = body.get("sourceId");
        if (source == null || source.isBlank() || sourceId == null || sourceId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("source 与 sourceId 不能为空"));
        }
        Audiobook book = scrapeService.bind(id, source, sourceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", book.getId());
        result.put("title", book.getTitle());
        result.put("author", book.getAuthor());
        result.put("narrator", book.getNarrator());
        result.put("overview", book.getOverview());
        result.put("series", book.getSeries());
        result.put("coverUrl", book.getCoverUrl());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id:\\d+}/scrape/unbind")
    @Operation(summary = "解绑刮削元数据", description = "清除刮削绑定标记，已写入的字段保留")
    public ResponseEntity<ApiResponse<Void>> scrapeUnbind(
            @PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        requireVisibleBook(id);
        scrapeService.unbind(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 工具 ──

    /** MULTI 模式章节：音轨累计为全局时间轴 */
    private List<AudiobookDetailDTO.ChapterDTO> buildGlobalChapters(List<AudiobookTrack> tracks) {
        List<AudiobookDetailDTO.ChapterDTO> chapters = new ArrayList<>();
        double global = 0;
        for (AudiobookTrack track : tracks) {
            double duration = track.getDurationSeconds() != null ? track.getDurationSeconds() : 0;
            chapters.add(AudiobookDetailDTO.ChapterDTO.builder()
                    .chapterIndex(track.getTrackIndex())
                    .title(track.getTitle())
                    .startSeconds(global)
                    .endSeconds(global + duration)
                    .trackIndex(track.getTrackIndex())
                    .startInTrack(0d)
                    .build());
            global += duration;
        }
        return chapters;
    }

    /** 进度换算为全书全局秒数（进度百分比用） */
    private double globalSecondsOf(AudiobookProgress progress, List<AudiobookTrack> tracks) {
        if (progress == null || progress.getTrackIndex() == null) return 0;
        double done = 0;
        for (AudiobookTrack track : tracks) {
            if (track.getTrackIndex() >= progress.getTrackIndex()) break;
            done += track.getDurationSeconds() != null ? track.getDurationSeconds() : 0;
        }
        return done + (progress.getPositionSeconds() != null ? progress.getPositionSeconds() : 0);
    }
}
