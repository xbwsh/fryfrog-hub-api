package com.fryfrog.hub.ebook.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ForbiddenException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.ebook.dto.EbookDetailDTO;
import com.fryfrog.hub.ebook.dto.EbookListDTO;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookProgress;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import com.fryfrog.hub.ebook.service.EbookProgressService;
import com.fryfrog.hub.ebook.service.EbookScrapeService;
import com.fryfrog.hub.ebook.service.EbookScanService;
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
@RequestMapping("/api/v1/ebooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "电子书管理", description = "电子书扫描、下载、阅读进度与刮削接口")
public class EbookController {

    private final EbookRepository bookRepository;
    private final EbookScanService scanService;
    private final EbookScrapeService scrapeService;
    private final EbookProgressService progressService;
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

    private Ebook requireVisibleBook(Long id) {
        Ebook book = bookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", id));
        if (book.getLibraryId() != null && !allowableIds().contains(book.getLibraryId())) {
            throw new ResourceNotFoundException("Ebook", "id", id);
        }
        return book;
    }

    // ── 扫描 ──

    @PostMapping("/scan")
    @Operation(summary = "扫描电子书资源库", description = "扫描指定 EBOOK 资源库（异步执行），不传 libraryId 时扫描全部")
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
                        .filter(MediaLibrary::isEbookType).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("libraryCount", libraries.size());
        Thread.startVirtualThread(() -> {
            for (MediaLibrary lib : libraries) {
                try {
                    scanService.scanAndSave(lib.getPath(), lib.getId());
                } catch (Exception e) {
                    log.warn("[EbookScan] Failed library '{}': {}", lib.getName(), e.getMessage());
                }
            }
        });
        return ResponseEntity.ok(ApiResponse.success("扫描任务已启动", result));
    }

    // ── 列表 / 详情 ──

    @GetMapping
    @Operation(summary = "电子书列表", description = "返回当前用户可见库中的电子书，支持分页与关键词搜索")
    public ResponseEntity<ApiResponse<PageResponse<EbookListDTO>>> list(
            @Parameter(description = "搜索关键词（书名模糊）") @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        List<Long> allowed = allowableIds();
        if (allowed.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(PageResponse.of(List.of(), page, size, 0)));
        }
        Page<Ebook> result;
        if (q != null && !q.isBlank()) {
            result = bookRepository.findByLibraryIdInAndTitleContainingIgnoreCase(
                    allowed, q, PageRequest.of(page, size, Sort.by("title").ascending()));
        } else {
            result = bookRepository.findByLibraryIdIn(allowed,
                    PageRequest.of(page, size, Sort.by("title").ascending()));
        }
        Map<Long, EbookProgress> progressMap = progressService.getProgressByBookIds(
                userId, result.getContent().stream().map(Ebook::getId).toList());
        List<EbookListDTO> dtos = result.getContent().stream()
                .map(b -> EbookListDTO.from(b, progressMap.get(b.getId())))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "电子书详情", description = "返回元数据与当前用户阅读进度")
    public ResponseEntity<ApiResponse<EbookDetailDTO>> detail(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        Ebook book = requireVisibleBook(id);
        EbookProgress progress = progressService.getProgress(userId, id);
        return ResponseEntity.ok(ApiResponse.success(EbookDetailDTO.from(book, progress)));
    }

    // ── 封面 / 原文件 ──

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面", description = "返回电子书封面图片")
    public ResponseEntity<FileSystemResource> cover(@PathVariable Long id) {
        Ebook book = requireVisibleBook(id);
        if (book.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }
        java.nio.file.Path path = Paths.get(book.getCoverArtPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(path.toFile()));
    }

    @GetMapping("/{id:\\d+}/file")
    @Operation(summary = "下载原文件", description = "返回电子书原文件（EPUB/PDF），前端自行渲染")
    public ResponseEntity<FileSystemResource> file(@PathVariable Long id) {
        Ebook book = requireVisibleBook(id);
        java.nio.file.Path path = Paths.get(book.getFilePath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(book.getFormat()))
                .body(new FileSystemResource(path.toFile()));
    }

    private MediaType mediaTypeOf(String format) {
        if (Ebook.FORMAT_EPUB.equals(format)) {
            return MediaType.parseMediaType("application/epub+zip");
        }
        if (Ebook.FORMAT_PDF.equals(format)) {
            return MediaType.APPLICATION_PDF;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    // ── 进度 ──

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "保存阅读进度", description = "body: {positionPercent, chapterIndex}；percent ≥ 95 自动标记读完")
    public ResponseEntity<ApiResponse<EbookDetailDTO.ProgressDTO>> saveProgress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        requireVisibleBook(id);
        Double percent = body.get("positionPercent") instanceof Number n ? n.doubleValue() : null;
        Integer chapterIndex = body.get("chapterIndex") instanceof Number n ? n.intValue() : null;
        EbookProgress progress = progressService.updatePosition(userId, id, percent, chapterIndex);
        return ResponseEntity.ok(ApiResponse.success(EbookDetailDTO.ProgressDTO.from(progress)));
    }

    @PutMapping("/{id:\\d+}/completed")
    @Operation(summary = "设置读完状态", description = "body: {completed}")
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
    @Operation(summary = "清除阅读进度")
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
    @Operation(summary = "编辑电子书元数据", description = "手动修正书名/作者/出版社/简介/系列（只更新传入的非空字段），同时清除刮削绑定")
    public ResponseEntity<ApiResponse<EbookDetailDTO>> updateMetadata(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        requireAdmin(req);
        Ebook book = requireVisibleBook(id);
        boolean updated = false;

        if (body.get("title") instanceof String s && !s.isBlank()) { book.setTitle(s); updated = true; }
        if (body.get("author") instanceof String s && !s.isBlank()) { book.setAuthor(s); updated = true; }
        if (body.get("publisher") instanceof String s) { book.setPublisher(s.isBlank() ? null : s); updated = true; }
        if (body.get("overview") instanceof String s) { book.setOverview(s.isBlank() ? null : s); updated = true; }
        if (body.get("series") instanceof String s) { book.setSeries(s.isBlank() ? null : s); updated = true; }
        if (body.get("seriesPart") instanceof Number n) { book.setSeriesPart(n.intValue()); updated = true; }

        if (updated) {
            book.setMetadataSource("manual");
            book.setSourceId(null);
            bookRepository.save(book);
            log.info("[Ebook] Metadata manually updated for book {}", id);
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
    public ResponseEntity<ApiResponse<List<com.fryfrog.hub.ebook.dto.EbookScrapeResult>>> scrapeSearch(
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
        requireVisibleBook(id);
        String source = body.get("source");
        String sourceId = body.get("sourceId");
        if (source == null || source.isBlank() || sourceId == null || sourceId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("source 与 sourceId 不能为空"));
        }
        Ebook book = scrapeService.bind(id, source, sourceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", book.getId());
        result.put("title", book.getTitle());
        result.put("author", book.getAuthor());
        result.put("publisher", book.getPublisher());
        result.put("pubYear", book.getPubYear());
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
}
