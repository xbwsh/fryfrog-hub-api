package com.fryfrog.hub.comic.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ForbiddenException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.comic.dto.ComicDetailDTO;
import com.fryfrog.hub.comic.dto.ComicListDTO;
import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicChapter;
import com.fryfrog.hub.comic.model.ComicProgress;
import com.fryfrog.hub.comic.repository.ComicChapterRepository;
import com.fryfrog.hub.comic.repository.ComicRepository;
import com.fryfrog.hub.comic.service.ComicPageService;
import com.fryfrog.hub.comic.service.ComicProgressService;
import com.fryfrog.hub.comic.service.ComicScrapeService;
import com.fryfrog.hub.comic.service.ComicScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/v1/comics")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "漫画管理", description = "漫画扫描、阅读、进度与刮削接口")
public class ComicController {

    private final ComicRepository comicRepository;
    private final ComicChapterRepository chapterRepository;
    private final ComicScanService scanService;
    private final ComicScrapeService scrapeService;
    private final ComicProgressService progressService;
    private final ComicPageService pageService;
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

    private Comic requireVisibleComic(Long id) {
        Comic comic = comicRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Comic", "id", id));
        if (comic.getLibraryId() != null && !allowableIds().contains(comic.getLibraryId())) {
            throw new ResourceNotFoundException("Comic", "id", id);
        }
        return comic;
    }

    // ── 扫描 ──

    @PostMapping("/scan")
    @Operation(summary = "扫描漫画资源库", description = "扫描指定 COMIC 资源库（异步执行），不传 libraryId 时扫描全部")
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
                        .filter(MediaLibrary::isComicType).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("libraryCount", libraries.size());
        Thread.startVirtualThread(() -> {
            for (MediaLibrary lib : libraries) {
                try {
                    scanService.scanAndSave(lib.getPath(), lib.getId());
                } catch (Exception e) {
                    log.warn("[ComicScan] Failed library '{}': {}", lib.getName(), e.getMessage());
                }
            }
        });
        return ResponseEntity.ok(ApiResponse.success("扫描任务已启动", result));
    }

    // ── 列表 / 详情 ──

    @GetMapping
    @Operation(summary = "漫画列表", description = "返回当前用户可见库中的漫画，支持分页与关键词搜索")
    public ResponseEntity<ApiResponse<PageResponse<ComicListDTO>>> list(
            @Parameter(description = "搜索关键词（书名模糊）") @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        List<Long> allowed = allowableIds();
        if (allowed.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(PageResponse.of(List.of(), page, size, 0)));
        }
        Page<Comic> result;
        if (q != null && !q.isBlank()) {
            result = comicRepository.findByLibraryIdInAndTitleContainingIgnoreCase(
                    allowed, q, PageRequest.of(page, size, Sort.by("title").ascending()));
        } else {
            result = comicRepository.findByLibraryIdIn(allowed,
                    PageRequest.of(page, size, Sort.by("title").ascending()));
        }
        List<Comic> content = result.getContent();
        Map<Long, ComicProgress> progressMap = progressService.getProgressByComicIds(
                userId, content.stream().map(Comic::getId).toList());
        List<ComicListDTO> dtos = content.stream()
                .map(c -> {
                    ComicProgress progress = progressMap.get(c.getId());
                    Double percent = progress == null ? null
                            : round1(progressService.progressPercent(progress,
                            chapterRepository.findByComic_IdOrderByChapterIndexAsc(c.getId())));
                    return ComicListDTO.from(c, progress, percent);
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "漫画详情", description = "返回元数据、卷/话列表与当前用户进度")
    public ResponseEntity<ApiResponse<ComicDetailDTO>> detail(
            @Parameter(description = "漫画ID") @PathVariable Long id,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        Comic comic = requireVisibleComic(id);
        List<ComicChapter> chapters = chapterRepository.findByComic_IdOrderByChapterIndexAsc(id);
        ComicProgress progress = progressService.getProgress(userId, id);
        Double percent = progress == null ? null
                : round1(progressService.progressPercent(progress, chapters));
        return ResponseEntity.ok(ApiResponse.success(ComicDetailDTO.from(comic,
                chapters.stream().map(ComicDetailDTO.ChapterDTO::from).toList(),
                progress, percent)));
    }

    // ── 封面 / 页图 ──

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面", description = "返回漫画封面图片")
    public ResponseEntity<FileSystemResource> cover(@PathVariable Long id) {
        Comic comic = requireVisibleComic(id);
        if (comic.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }
        java.nio.file.Path path = Paths.get(comic.getCoverArtPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(path.toFile()));
    }

    @GetMapping("/chapters/{chapterId:\\d+}/pages")
    @Operation(summary = "页图 URL 列表", description = "返回章节内每页的签名 URL（index 为自然序位置）")
    public ResponseEntity<ApiResponse<List<String>>> pages(
            @Parameter(description = "卷/话ID") @PathVariable Long chapterId,
            HttpServletRequest request) {
        ComicChapter chapter = requireVisibleChapter(chapterId);
        try {
            return ResponseEntity.ok(ApiResponse.success(pageService.pageUrls(chapter)));
        } catch (Exception e) {
            throw new ResourceNotFoundException("ComicChapter", "id", chapterId);
        }
    }

    @GetMapping("/chapters/{chapterId:\\d+}/pages/{index:\\d+}")
    @Operation(summary = "页图内容", description = "返回指定索引的页图（支持签名 URL 直连）")
    public ResponseEntity<?> page(
            @Parameter(description = "卷/话ID") @PathVariable Long chapterId,
            @Parameter(description = "页索引") @PathVariable int index) {
        ComicChapter chapter = requireVisibleChapter(chapterId);
        try {
            var resource = pageService.readPage(chapter, index);
            return ResponseEntity.ok()
                    .contentType(pageService.mediaTypeOf(resource.getFilename()))
                    .body(resource);
        } catch (Exception e) {
            if (e instanceof ResourceNotFoundException) throw (ResourceNotFoundException) e;
            return ResponseEntity.notFound().build();
        }
    }

    private ComicChapter requireVisibleChapter(Long chapterId) {
        ComicChapter chapter = chapterRepository.findWithComicById(chapterId)
                .orElseThrow(() -> new ResourceNotFoundException("ComicChapter", "id", chapterId));
        requireVisibleComic(chapter.getComic().getId());
        return chapter;
    }

    // ── 进度 ──

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "保存阅读进度", description = "body: {chapterIndex, pageIndex}；读到最后一卷末页自动标记读完")
    public ResponseEntity<ApiResponse<ComicDetailDTO.ProgressDTO>> saveProgress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleComic(id);
        Integer chapterIndex = body.get("chapterIndex") instanceof Number n ? n.intValue() : null;
        Integer pageIndex = body.get("pageIndex") instanceof Number n ? n.intValue() : null;
        ComicProgress progress = progressService.updatePosition(userId, id, chapterIndex, pageIndex);
        List<ComicChapter> chapters = chapterRepository.findByComic_IdOrderByChapterIndexAsc(id);
        return ResponseEntity.ok(ApiResponse.success(ComicDetailDTO.ProgressDTO.from(progress,
                round1(progressService.progressPercent(progress, chapters)))));
    }

    @PutMapping("/{id:\\d+}/completed")
    @Operation(summary = "设置读完状态", description = "body: {completed}")
    public ResponseEntity<ApiResponse<Void>> setCompleted(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleComic(id);
        boolean completed = Boolean.TRUE.equals(body.get("completed"));
        progressService.setCompleted(userId, id, completed);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "清除阅读进度")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(@PathVariable Long id, HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleComic(id);
        progressService.deleteProgress(userId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 元数据编辑 / 刮削 ──

    @PutMapping("/{id:\\d+}/metadata")
    @Operation(summary = "编辑漫画元数据", description = "手动修正书名/作者/简介/系列（只更新传入的非空字段），同时清除刮削绑定")
    public ResponseEntity<ApiResponse<ComicDetailDTO>> updateMetadata(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        requireAdmin(req);
        Comic comic = requireVisibleComic(id);
        boolean updated = false;

        if (body.get("title") instanceof String s && !s.isBlank()) { comic.setTitle(s); updated = true; }
        if (body.get("author") instanceof String s && !s.isBlank()) { comic.setAuthor(s); updated = true; }
        if (body.get("overview") instanceof String s) { comic.setOverview(s.isBlank() ? null : s); updated = true; }
        if (body.get("series") instanceof String s) { comic.setSeries(s.isBlank() ? null : s); updated = true; }
        if (body.get("seriesPart") instanceof Number n) { comic.setSeriesPart(n.intValue()); updated = true; }

        if (updated) {
            comic.setMetadataSource("manual");
            comic.setSourceId(null);
            comicRepository.save(comic);
            log.info("[Comic] Metadata manually updated for comic {}", id);
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
    public ResponseEntity<ApiResponse<List<com.fryfrog.hub.comic.dto.ComicScrapeResult>>> scrapeSearch(
            @RequestParam String q,
            @RequestParam(required = false) String source,
            HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(ApiResponse.success(scrapeService.search(q, source)));
    }

    @PostMapping("/{id:\\d+}/scrape/bind")
    @Operation(summary = "绑定刮削元数据", description = "body: {source, sourceId}；结果落库并下载封面，不改文件")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scrapeBind(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        requireAdmin(request);
        requireVisibleComic(id);
        String source = body.get("source");
        String sourceId = body.get("sourceId");
        if (source == null || source.isBlank() || sourceId == null || sourceId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("source 与 sourceId 不能为空"));
        }
        Comic comic = scrapeService.bind(id, source, sourceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", comic.getId());
        result.put("title", comic.getTitle());
        result.put("author", comic.getAuthor());
        result.put("pubYear", comic.getPubYear());
        result.put("rating", comic.getRating());
        result.put("overview", comic.getOverview());
        result.put("series", comic.getSeries());
        result.put("coverUrl", comic.getCoverUrl());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id:\\d+}/scrape/unbind")
    @Operation(summary = "解绑刮削元数据", description = "清除刮削绑定标记，已写入的字段保留")
    public ResponseEntity<ApiResponse<Void>> scrapeUnbind(
            @PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        requireVisibleComic(id);
        scrapeService.unbind(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 工具 ──

    private static Double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
