package com.fryfrog.hub.ebook.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.dto.ScrapeProgress;
import com.fryfrog.hub.common.model.MediaSeriesCharacter;
import com.fryfrog.hub.common.repository.MediaSeriesCharacterRepository;
import com.fryfrog.hub.common.service.BangumiService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.ebook.dto.ChapterInfo;
import com.fryfrog.hub.ebook.dto.EbookDTO;
import com.fryfrog.hub.ebook.dto.EbookReadingProgressDTO;
import com.fryfrog.hub.ebook.dto.EbookReadingProgressRequest;
import com.fryfrog.hub.ebook.dto.EbookSeries;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookReadingProgress;
import com.fryfrog.hub.ebook.service.EbookMetadataScrapeService;
import com.fryfrog.hub.ebook.service.EbookBangumiScrapeService;
import com.fryfrog.hub.ebook.service.EbookReadingProgressService;
import com.fryfrog.hub.ebook.service.OpenLibraryService;
import com.fryfrog.hub.ebook.service.EbookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

import com.fryfrog.hub.ebook.util.EpubParser;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ebook")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "电子书管理", description = "电子书元数据查询、扫描接口")
public class EbookController {

    private final EbookService service;
    private final EbookReadingProgressService readingProgressService;
    private final EbookMetadataScrapeService scrapeService;
    private final EbookBangumiScrapeService bangumiScrapeService;
    private final OpenLibraryService openLibraryService;
    private final ScrapeProgressService scrapeProgressService;
    private final MediaSeriesCharacterRepository mediaCharacterRepository;
    @Qualifier("scraperRestTemplate")
    private final RestTemplate scraperRestTemplate;

    @Value("${ebook.root-paths:}")
    private String rootPathsConfig;

    private List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @GetMapping("/series")
    @Operation(summary = "按系列分组获取电子书", description = "返回按系列分组的电子书列表，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<EbookSeries>>> getEbooksBySeries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<EbookSeries> allSeries = service.getEbooksBySeries();
        long total = allSeries.size();
        int start = page * size;
        int end = Math.min(start + size, allSeries.size());
        List<EbookSeries> paged = start < allSeries.size() ? allSeries.subList(start, end) : List.of();
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(paged, page, size, total)));
    }

    @GetMapping("/series/cover")
    @Operation(summary = "获取系列封面", description = "根据系列名返回系列封面图片")
    public ResponseEntity<Resource> getSeriesCover(
            @Parameter(description = "系列名") @RequestParam String series) {
        List<EbookSeries> seriesList = service.getEbooksBySeries();
        EbookSeries target = seriesList.stream()
                .filter(s -> series.equals(s.getName()))
                .findFirst()
                .orElse(null);
        if (target == null || target.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }
        File coverFile = new File(target.getCoverArtPath());
        if (!coverFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(resolveImageMediaType(coverFile.getName()))
                .body(new FileSystemResource(coverFile));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取电子书详情", description = "根据ID获取单个电子书的详细信息")
    public ResponseEntity<ApiResponse<EbookDTO>> getEbookById(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        EbookDTO ebook = service.getEbookById(id);
        return ResponseEntity.ok(ApiResponse.success(ebook));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按书名搜索", description = "根据书名关键词模糊搜索电子书")
    public ResponseEntity<ApiResponse<PageResponse<EbookDTO>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByTitle(q, page, size)));
    }

    @GetMapping("/search/author")
    @Operation(summary = "按作者搜索", description = "根据作者名称模糊搜索电子书")
    public ResponseEntity<ApiResponse<PageResponse<EbookDTO>>> searchByAuthor(
            @Parameter(description = "作者名称关键词") @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByAuthor(q, page, size)));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回已收藏的电子书，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<EbookDTO>>> getFavorites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.getFavorites(page, size)));
    }

    @GetMapping("/recently-added")
    @Operation(summary = "最近添加", description = "返回最近添加的电子书，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<EbookDTO>>> getRecentlyAdded(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.getRecentlyAdded(page, size)));
    }

    @GetMapping("/recently-read")
    @Operation(summary = "最近阅读", description = "返回最近阅读过的电子书")
    public ResponseEntity<ApiResponse<List<EbookReadingProgressDTO>>> getRecentlyRead() {
        return ResponseEntity.ok(ApiResponse.success(service.getRecentlyRead()));
    }

    @GetMapping("/stats")
    @Operation(summary = "阅读统计", description = "返回电子书阅读统计数据")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(service.getStats()));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置电子书的收藏状态")
    public ResponseEntity<ApiResponse<EbookDTO>> setFavorite(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        EbookDTO ebook = service.setFavorite(id, status);
        return ResponseEntity.ok(ApiResponse.success(ebook));
    }

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面图片", description = "返回电子书的封面图片，无封面时返回标题占位图")
    public ResponseEntity<Resource> getCoverArt(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        Ebook ebook = service.getEbookEntityById(id);

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

    // 旧的 cover-image 端点已移除，请使用 /{id}/cover 端点

    @GetMapping("/{id:\\d+}/characters")
    @Operation(summary = "获取电子书角色列表", description = "返回同系列所有电子书的角色信息（去重）")
    public ResponseEntity<ApiResponse<List<MediaSeriesCharacter>>> getCharacters(
            @Parameter(description = "电子书ID") @PathVariable Long id) {
        Ebook ebook = service.getEbookEntityById(id);
        if (ebook.getSeriesRef() == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<MediaSeriesCharacter> characters =
                mediaCharacterRepository.findBySeries_Id(ebook.getSeriesRef().getId());
        return ResponseEntity.ok(ApiResponse.success(characters));
    }

    @GetMapping("/character/{id:\\d+}/image")
    @Operation(summary = "获取角色图片", description = "返回角色图片，优先本地文件，无则重定向到远程URL")
    public ResponseEntity<Resource> getCharacterImage(
            @Parameter(description = "角色ID") @PathVariable Long id) {
        MediaSeriesCharacter character = mediaCharacterRepository.findById(id).orElse(null);
        if (character == null) {
            return ResponseEntity.notFound().build();
        }

        if (character.getImagePath() != null) {
            File localFile = new File(character.getImagePath());
            if (localFile.exists()) {
                return ResponseEntity.ok()
                        .contentType(resolveImageMediaType(localFile.getName()))
                        .body(new FileSystemResource(localFile));
            }
        }

        if (character.getImageUrl() != null && !character.getImageUrl().isBlank()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, character.getImageUrl())
                    .build();
        }

        return ResponseEntity.notFound().build();
    }

    private MediaType resolveImageMediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    @GetMapping("/{id:\\d+}/read")
    @Operation(summary = "在线阅读", description = "返回电子书内容。chapter=0或不传返回整书，chapter>0返回指定章节")
    public ResponseEntity<String> readEbook(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "章节序号（从1开始，0或不传返回整书）") @RequestParam(defaultValue = "0") int chapter) {
        if (chapter > 0) {
            String content = service.getChapterContent(id, chapter);
            Ebook ebook = service.getEbookEntityById(id);
            MediaType type = EpubParser.isEpub(ebook.getFilePath()) ? MediaType.TEXT_HTML : MediaType.TEXT_PLAIN;
            return ResponseEntity.ok().contentType(type).body(content);
        }

        Ebook ebook = service.getEbookEntityById(id);
        File file = new File(ebook.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        try {
            if (EpubParser.isEpub(ebook.getFilePath())) {
                List<EpubParser.ChapterEntry> chapters = EpubParser.extractChapters(ebook.getFilePath());
                StringBuilder sb = new StringBuilder();
                for (EpubParser.ChapterEntry ch : chapters) {
                    String html = EpubParser.readChapterHtml(ebook.getFilePath(), ch.href(), ebook.getId());
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

    @GetMapping("/{id:\\d+}/image")
    @Operation(summary = "获取epub内嵌图片", description = "从epub文件中提取指定路径的图片")
    public ResponseEntity<Resource> getEpubImage(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "图片在epub内的路径") @RequestParam String file) {
        Ebook ebook = service.getEbookEntityById(id);
        String filePath = ebook.getFilePath();
        if (filePath == null || !new File(filePath).exists()) {
            return ResponseEntity.notFound().build();
        }
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
                    .body(new ByteArrayResource(image));
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
        Ebook ebook = service.getEbookEntityById(id);
        if (EpubParser.isEpub(ebook.getFilePath())) {
            try {
                List<EpubParser.ChapterEntry> chapters = EpubParser.extractChapters(ebook.getFilePath());
                if (chapterNum < 1 || chapterNum > chapters.size()) {
                    throw new IllegalArgumentException("Chapter number out of range: " + chapterNum);
                }
                EpubParser.ChapterEntry chapter = chapters.get(chapterNum - 1);
                String html = EpubParser.readChapterHtml(ebook.getFilePath(), chapter.href(), ebook.getId());
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
        Ebook ebook = service.getEbookEntityById(id);
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
            @RequestBody EbookReadingProgressRequest request) {
        Integer page = request.getCurrentPage();
        Integer totalPages = request.getTotalPages();
        if (page == null || page < 1) page = 1;
        if (totalPages == null || totalPages < 1) totalPages = 1;
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

    @PostMapping("/auto-scrape")
    @Operation(summary = "批量自动刮削", description = "自动刮削所有未绑定的电子书（异步执行）")
    public ResponseEntity<ApiResponse<Void>> autoScrape() {
        scrapeService.autoScrapeAll();
        return ResponseEntity.ok(ApiResponse.success("批量刮削已开始", null));
    }

    @GetMapping("/openlibrary/search")
    @Operation(summary = "搜索 Open Library 书籍", description = "在 Open Library 上搜索已出版的图书")
    public ResponseEntity<ApiResponse<List<OpenLibraryService.SearchResult>>> searchOpenLibrary(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(openLibraryService.searchBooks(q)));
    }

    @PostMapping("/{id:\\d+}/openlibrary/bind")
    @Operation(summary = "绑定 Open Library 元数据", description = "将 Open Library 条目的元数据绑定到指定电子书，下载封面到本地")
    public ResponseEntity<ApiResponse<Ebook>> bindOpenLibrary(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @RequestParam String olid) {
        List<OpenLibraryService.SearchResult> results = openLibraryService.searchBooks(olid);
        if (results.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No Open Library results for: " + olid));
        }
        OpenLibraryService.SearchResult match = results.stream()
                .filter(r -> olid.equals(r.getOlid()))
                .findFirst()
                .orElse(results.get(0));
        return ResponseEntity.ok(ApiResponse.success(scrapeService.scrapeAndBindFromOpenLibrary(id, match)));
    }

    @GetMapping("/{id:\\d+}/bangumi/search")
    @Operation(summary = "搜索 Bangumi 轻小说", description = "在 Bangumi 上搜索轻小说/小说条目（默认过滤轻小说，subType=manga 为漫画）")
    public ResponseEntity<ApiResponse<List<BangumiService.SearchResult>>> searchBangumi(
            @Parameter(description = "搜索关键词") @RequestParam String q,
            @Parameter(description = "子类型：novel=轻小说, manga=漫画, 默认不过滤") @RequestParam(required = false) String subType) {
        return ResponseEntity.ok(ApiResponse.success(bangumiScrapeService.searchFromBangumi(q, subType)));
    }

    @PostMapping("/{id:\\d+}/bangumi/bind")
    @Operation(summary = "绑定 Bangumi 元数据", description = "将指定 Bangumi 条目的元数据绑定到电子书，下载封面到本地")
    public ResponseEntity<ApiResponse<Ebook>> bindBangumi(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @Parameter(description = "Bangumi 条目 ID") @RequestParam Integer bangumiId) {
        return ResponseEntity.ok(ApiResponse.success(bangumiScrapeService.bindBangumi(id, bangumiId)));
    }

    @PostMapping("/series/rescrape")
    @Operation(summary = "按系列名重新刮削", description = "重新获取系列简介、系列封面、每个卷的封面和简介")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rescrapeSeries(
            @Parameter(description = "系列名") @RequestParam String series) {
        int updated = bangumiScrapeService.rescrapeSeries(series);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("series", series);
        result.put("updated", updated);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/image-proxy")
    @Operation(summary = "代理远程图片", description = "代理加载远程封面图片（Bangumi CDN 前端无法直连时使用）")
    public ResponseEntity<Resource> imageProxy(
            @Parameter(description = "图片 URL") @RequestParam String url) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/0.1.0");
            org.springframework.http.HttpEntity<Void> request = new org.springframework.http.HttpEntity<>(headers);
            ResponseEntity<byte[]> response = scraperRestTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET, request, byte[].class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                org.springframework.http.MediaType mediaType = org.springframework.http.MediaType.IMAGE_JPEG;
                if (url.toLowerCase().contains(".png")) mediaType = org.springframework.http.MediaType.IMAGE_PNG;
                else if (url.toLowerCase().contains(".gif")) mediaType = org.springframework.http.MediaType.IMAGE_GIF;
                else if (url.toLowerCase().contains(".webp")) mediaType = org.springframework.http.MediaType.parseMediaType("image/webp");
                return ResponseEntity.ok()
                        .contentType(mediaType)
                        .body(new org.springframework.core.io.ByteArrayResource(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to proxy image: {}", e.getMessage());
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/scrape/progress")
    @Operation(summary = "刮削进度", description = "返回当前电子书刮削任务的进度")
    public ResponseEntity<ApiResponse<ScrapeProgress>> scrapeProgress() {
        return ResponseEntity.ok(ApiResponse.success(scrapeProgressService.getProgress("ebook")));
    }

    @PutMapping("/{id:\\d+}/metadata")
    @Operation(summary = "手动更新元数据", description = "手动更新电子书的元数据信息")
    public ResponseEntity<ApiResponse<Ebook>> updateMetadata(
            @Parameter(description = "电子书ID") @PathVariable Long id,
            @RequestBody Map<String, Object> metadata) {
        return ResponseEntity.ok(ApiResponse.success(scrapeService.updateMetadata(id, metadata)));
    }

    private void validatePath(String path) {
        Path requestedPath = Paths.get(path).toAbsolutePath();
        boolean allowed = getRootPaths().stream()
                .anyMatch(root -> requestedPath.startsWith(Paths.get(root).toAbsolutePath()));
        if (!allowed) {
            throw new IllegalArgumentException("Path is outside allowed root paths");
        }
    }
}
