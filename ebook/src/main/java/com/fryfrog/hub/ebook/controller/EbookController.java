package com.fryfrog.hub.ebook.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.ebook.dto.BookSearchResult;
import com.fryfrog.hub.ebook.dto.ChapterInfo;
import com.fryfrog.hub.ebook.dto.EbookReadingProgressDTO;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookReadingProgress;
import com.fryfrog.hub.ebook.service.EbookReadingProgressService;
import com.fryfrog.hub.ebook.service.EbookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import com.fryfrog.hub.ebook.util.EpubParser;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ebook")
@RequiredArgsConstructor
@Tag(name = "电子书管理", description = "电子书元数据查询、扫描接口")
public class EbookController {

    private final EbookService service;
    private final EbookReadingProgressService readingProgressService;

    @Value("${hub.ebook.root-path}")
    private String rootPath;

    @GetMapping
    @Operation(summary = "获取所有电子书", description = "返回数据库中所有已索引的电子书列表")
    public ResponseEntity<ApiResponse<List<Ebook>>> getAllEbooks() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllEbooks()));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取电子书详情", description = "根据ID获取单个电子书的详细信息")
    public ResponseEntity<ApiResponse<Ebook>> getEbookById(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getEbookById(id)));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按书名搜索", description = "根据书名关键词模糊搜索电子书")
    public ResponseEntity<ApiResponse<List<Ebook>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByTitle(q)));
    }

    @GetMapping("/search/author")
    @Operation(summary = "按作者搜索", description = "根据作者名称模糊搜索电子书")
    public ResponseEntity<ApiResponse<List<Ebook>>> searchByAuthor(
            @Parameter(description = "作者名称关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByAuthor(q)));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回所有已收藏的电子书")
    public ResponseEntity<ApiResponse<List<Ebook>>> getFavorites() {
        return ResponseEntity.ok(ApiResponse.success(service.getFavorites()));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置电子书的收藏状态")
    public ResponseEntity<ApiResponse<Ebook>> setFavorite(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        return ResponseEntity.ok(ApiResponse.success(service.setFavorite(id, status)));
    }

    @PostMapping("/scan")
    @Operation(summary = "扫描电子书目录", description = "递归扫描指定目录，提取所有支持格式的电子书文件元数据并入库")
    public ResponseEntity<ApiResponse<String>> scanDirectory(
            @Parameter(description = "要扫描的目录路径（必须在配置的根目录内）") @RequestParam String path) {
        validatePath(path);
        service.scanDirectory(path);
        return ResponseEntity.ok(ApiResponse.success("Scan completed", path));
    }

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面图片", description = "返回电子书的封面图片，无封面时返回标题占位图")
    public ResponseEntity<Resource> getCoverArt(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        Ebook ebook = service.getEbookById(id);

        if (ebook.getCoverArtPath() != null) {
            File coverFile = new File(ebook.getCoverArtPath());
            if (coverFile.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(new FileSystemResource(coverFile));
            }
        }

        try {
            byte[] placeholder = PlaceholderImageGenerator.generate(ebook.getTitle(), 300, 400);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(placeholder));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id:\\d+}/read")
    @Operation(summary = "在线阅读", description = "返回电子书的内容，epub返回HTML，其他返回纯文本")
    public ResponseEntity<String> readEbook(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        Ebook ebook = service.getEbookById(id);
        File file = new File(ebook.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        try {
            if (EpubParser.isEpub(ebook.getFilePath())) {
                List<EpubParser.ChapterEntry> chapters = EpubParser.extractChapters(ebook.getFilePath());
                StringBuilder sb = new StringBuilder();
                for (EpubParser.ChapterEntry ch : chapters) {
                    String html = EpubParser.readChapterHtml(ebook.getFilePath(), ch.href());
                    if (!html.isBlank()) {
                        if (!sb.isEmpty()) sb.append("\n<hr>\n");
                        sb.append(html);
                    }
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(sb.toString());
            } else {
                String content = java.nio.file.Files.readString(file.toPath());
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(content);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read ebook: " + e.getMessage(), e);
        }
    }

    @GetMapping("/epub-image")
    @Operation(summary = "获取epub内嵌图片", description = "从epub文件中提取指定路径的图片")
    public ResponseEntity<byte[]> getEpubImage(
            @Parameter(description = "电子书文件完整路径") @RequestParam String filePath,
            @Parameter(description = "图片在epub内的路径") @RequestParam String file) {
        try {
            byte[] image = EpubParser.readImage(filePath, file);
            if (image == null) {
                return ResponseEntity.notFound().build();
            }
            String lower = file.toLowerCase();
            MediaType mediaType = MediaType.IMAGE_JPEG;
            if (lower.endsWith(".png")) mediaType = MediaType.IMAGE_PNG;
            else if (lower.endsWith(".gif")) mediaType = MediaType.IMAGE_GIF;
            else if (lower.endsWith(".webp")) mediaType = MediaType.parseMediaType("image/webp");
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(image);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read epub image: " + e.getMessage(), e);
        }
    }

    @GetMapping("/{id:\\d+}/chapters")
    @Operation(summary = "获取章节列表", description = "返回电子书的章节列表（按标题模式自动识别）")
    public ResponseEntity<ApiResponse<List<ChapterInfo>>> getChapterList(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getChapterList(id)));
    }

    @GetMapping("/{id:\\d+}/chapters/{chapterNum}")
    @Operation(summary = "获取章节内容", description = "返回指定章节内容，epub返回HTML，其他返回纯文本")
    public ResponseEntity<String> getChapterContent(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "章节序号（从1开始）") @PathVariable int chapterNum) {
        Ebook ebook = service.getEbookById(id);
        if (EpubParser.isEpub(ebook.getFilePath())) {
            try {
                List<EpubParser.ChapterEntry> chapters = EpubParser.extractChapters(ebook.getFilePath());
                if (chapterNum < 1 || chapterNum > chapters.size()) {
                    throw new IllegalArgumentException("Chapter number out of range: " + chapterNum);
                }
                EpubParser.ChapterEntry chapter = chapters.get(chapterNum - 1);
                String html = EpubParser.readChapterHtml(ebook.getFilePath(), chapter.href());
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(html);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to read epub chapter: " + e.getMessage(), e);
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(service.getChapterContent(id, chapterNum));
    }

    @GetMapping("/{id:\\d+}/download")
    @Operation(summary = "下载电子书", description = "返回电子书文件供下载")
    public ResponseEntity<Resource> downloadEbook(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        Ebook ebook = service.getEbookById(id);
        File file = new File(ebook.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ebook.getFileName() + "\"")
                .body(new FileSystemResource(file));
    }

    @GetMapping("/{id:\\d+}/progress")
    @Operation(summary = "获取阅读进度", description = "获取指定电子书的阅读进度")
    public ResponseEntity<ApiResponse<EbookReadingProgressDTO>> getProgress(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        EbookReadingProgress progress = readingProgressService.getProgress(id);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        return ResponseEntity.ok(ApiResponse.success(EbookReadingProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "保存阅读进度", description = "保存指定电子书的阅读进度")
    public ResponseEntity<ApiResponse<EbookReadingProgressDTO>> saveProgress(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "当前页码/章节数") @RequestParam Integer page,
            @Parameter(description = "总页数/章节数") @RequestParam Integer totalPages) {
        EbookReadingProgress progress = readingProgressService.saveProgress(id, page, totalPages);
        return ResponseEntity.ok(ApiResponse.success(EbookReadingProgressDTO.fromEntity(progress)));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "删除阅读进度", description = "清除指定电子书的阅读进度")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        readingProgressService.deleteProgress(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/metadata/search")
    @Operation(summary = "搜索书籍元数据", description = "在多个数据源中搜索书籍元数据")
    public ResponseEntity<ApiResponse<List<BookSearchResult>>> searchBookMetadata(
            @Parameter(description = "书名关键词") @RequestParam String title,
            @Parameter(description = "作者名称（可选）") @RequestParam(required = false) String author) {
        return ResponseEntity.ok(ApiResponse.success(service.searchBooks(title, author)));
    }

    @PostMapping("/{id:\\d+}/metadata/bind")
    @Operation(summary = "绑定书籍元数据", description = "将搜索到的元数据绑定到指定电子书")
    public ResponseEntity<ApiResponse<Ebook>> bindBookMetadata(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "搜索结果") @RequestBody BookSearchResult searchResult) {
        return ResponseEntity.ok(ApiResponse.success(service.scrapeAndSave(id, searchResult)));
    }

    @PostMapping("/metadata/auto-scrape")
    @Operation(summary = "自动刮削所有电子书", description = "自动为所有未刮削的电子书搜索并绑定元数据")
    public ResponseEntity<ApiResponse<String>> autoScrapeAll() {
        int scraped = service.autoScrape();
        return ResponseEntity.ok(ApiResponse.success(
                String.format("Auto-scrape completed: %d ebooks scraped", scraped), ""));
    }

    private void validatePath(String path) {
        Path requestedPath = Paths.get(path).toAbsolutePath();
        Path allowedRoot = Paths.get(rootPath).toAbsolutePath();
        if (!requestedPath.startsWith(allowedRoot)) {
            throw new IllegalArgumentException("Path is outside allowed root: " + rootPath);
        }
    }
}
