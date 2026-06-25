package com.fryfrog.hub.comic.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.comic.dto.ComicBindRequest;
import com.fryfrog.hub.comic.dto.ComicReadingProgressDTO;
import com.fryfrog.hub.comic.dto.ComicReadingProgressRequest;
import com.fryfrog.hub.comic.dto.ComicSeries;
import com.fryfrog.hub.comic.dto.PageInfo;
import com.fryfrog.hub.comic.dto.anilist.AnilistSearchResult;
import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicCharacter;
import com.fryfrog.hub.comic.model.ComicReadingProgress;
import com.fryfrog.hub.comic.repository.ComicCharacterRepository;
import com.fryfrog.hub.comic.service.BangumiService;
import com.fryfrog.hub.comic.service.ComicMetadataService;
import com.fryfrog.hub.comic.service.ComicReadingProgressService;
import com.fryfrog.hub.comic.service.MangaScrapeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Async;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/comic")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "漫画管理", description = "漫画元数据查询、扫描接口")
public class ComicController {

    private final ComicMetadataService service;
    private final ComicReadingProgressService readingProgressService;
    private final MangaScrapeService mangaScrapeService;
    private final ComicCharacterRepository characterRepository;

    @Value("${hub.comic.root-paths:./media-library/comic}")
    private String rootPathsConfig;

    private List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @GetMapping
    @Operation(summary = "获取所有漫画", description = "返回数据库中所有已索引的漫画列表")
    public ResponseEntity<ApiResponse<List<Comic>>> getAllComics() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllComics()));
    }

    @GetMapping("/series")
    @Operation(summary = "按系列分组获取漫画", description = "返回按系列分组的漫画列表，同一系列的漫画归为一组")
    public ResponseEntity<ApiResponse<List<ComicSeries>>> getComicsBySeries() {
        return ResponseEntity.ok(ApiResponse.success(service.getComicsBySeries()));
    }

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取漫画详情", description = "根据ID获取单个漫画的详细信息")
    public ResponseEntity<ApiResponse<Comic>> getComicById(
            @Parameter(description = "漫画ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getComicById(id)));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按标题搜索", description = "根据标题关键词模糊搜索漫画")
    public ResponseEntity<ApiResponse<List<Comic>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByTitle(q)));
    }

    @GetMapping("/search/author")
    @Operation(summary = "按作者搜索", description = "根据作者名称模糊搜索漫画")
    public ResponseEntity<ApiResponse<List<Comic>>> searchByAuthor(
            @Parameter(description = "作者名称关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByAuthor(q)));
    }

    @PostMapping("/scan")
    @Operation(summary = "扫描漫画目录", description = "递归扫描指定目录，提取所有支持格式的漫画文件元数据并入库")
    public ResponseEntity<ApiResponse<String>> scanDirectory(
            @Parameter(description = "要扫描的目录路径（必须在配置的根目录内）") @RequestParam String path) {
        validatePath(path);
        service.scanDirectory(path);
        return ResponseEntity.ok(ApiResponse.success("Scan completed", path));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回所有已收藏的漫画")
    public ResponseEntity<ApiResponse<List<Comic>>> getFavorites() {
        return ResponseEntity.ok(ApiResponse.success(service.getFavorites()));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置漫画的收藏状态")
    public ResponseEntity<ApiResponse<Comic>> setFavorite(
            @Parameter(description = "漫画ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        return ResponseEntity.ok(ApiResponse.success(service.setFavorite(id, status)));
    }

    @GetMapping("/{id:\\d+}/pages")
    @Operation(summary = "获取页面列表", description = "返回漫画的页面列表信息，包含总页数和各页文件名")
    public ResponseEntity<ApiResponse<List<PageInfo>>> getPageList(
            @Parameter(description = "漫画ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getPageList(id)));
    }

    @GetMapping("/{id:\\d+}/pages/{pageNum}")
    @Operation(summary = "获取页面图片", description = "返回漫画指定页码的图片数据")
    public ResponseEntity<byte[]> getPageImage(
            @Parameter(description = "漫画ID") @PathVariable Long id,
            @Parameter(description = "页码（从1开始）") @PathVariable int pageNum) {
        byte[] imageBytes = service.getPageImage(id, pageNum);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(imageBytes);
    }

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面图片", description = "返回漫画的封面图片")
    public ResponseEntity<Resource> getCoverArt(
            @Parameter(description = "漫画ID") @PathVariable Long id) {
        Comic comic = service.getComicById(id);
        if (comic.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }
        File coverFile = new File(comic.getCoverArtPath());
        if (!coverFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(coverFile));
    }

    @GetMapping("/{id:\\d+}/characters")
    @Operation(summary = "获取漫画角色列表", description = "返回指定漫画的角色信息列表")
    public ResponseEntity<ApiResponse<List<ComicCharacter>>> getCharacters(
            @Parameter(description = "漫画ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(characterRepository.findByComicId(id)));
    }

    @GetMapping("/character/{id:\\d+}/image")
    @Operation(summary = "获取角色图片", description = "返回角色图片，优先本地文件，无则重定向到远程URL")
    public ResponseEntity<Resource> getCharacterImage(
            @Parameter(description = "角色ID") @PathVariable Long id) {
        ComicCharacter character = characterRepository.findById(id).orElse(null);
        if (character == null) {
            return ResponseEntity.notFound().build();
        }

        if (character.getImagePath() != null) {
            File localFile = new File(character.getImagePath());
            if (localFile.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
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

    @GetMapping("/cover-image")
    @Operation(summary = "按路径获取封面图片", description = "根据coverArtPath返回封面图片")
    public ResponseEntity<Resource> getCoverImage(
            @Parameter(description = "封面图片完整路径") @RequestParam String path) {
        File coverFile = new File(path);
        if (!coverFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        String name = coverFile.getName().toLowerCase();
        MediaType mediaType = MediaType.IMAGE_JPEG;
        if (name.endsWith(".png")) mediaType = MediaType.IMAGE_PNG;
        else if (name.endsWith(".webp")) mediaType = MediaType.parseMediaType("image/webp");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new FileSystemResource(coverFile));
    }

    @GetMapping("/{id:\\d+}/progress")
    @Operation(summary = "获取阅读进度", description = "获取指定漫画的阅读进度")
    public ResponseEntity<ApiResponse<ComicReadingProgressDTO>> getProgress(
            @Parameter(description = "漫画ID") @PathVariable Long id) {
        ComicReadingProgress progress = readingProgressService.getProgress(id);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        return ResponseEntity.ok(ApiResponse.success(ComicReadingProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "保存阅读进度", description = "保存指定漫画的阅读进度")
    public ResponseEntity<ApiResponse<ComicReadingProgressDTO>> saveProgress(
            @Parameter(description = "漫画ID") @PathVariable Long id,
            @RequestBody ComicReadingProgressRequest request) {
        log.info("PUT progress comicId={}, currentPage={}, totalPages={}", id, request.getCurrentPage(), request.getTotalPages());
        Integer page = request.getCurrentPage();
        Integer totalPages = request.getTotalPages();
        if (page == null || page < 1) page = 1;
        if (totalPages == null || totalPages < 1) totalPages = 1;
        ComicReadingProgress progress = readingProgressService.saveProgress(id, page, totalPages);
        return ResponseEntity.ok(ApiResponse.success(ComicReadingProgressDTO.fromEntity(progress)));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "删除阅读进度", description = "清除指定漫画的阅读进度")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(
            @Parameter(description = "漫画ID") @PathVariable Long id) {
        readingProgressService.deleteProgress(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/organize")
    @Operation(summary = "批量整理漫画", description = "将漫画按系列归类到文件夹，清洗文件名中的无用标签")
    public ResponseEntity<ApiResponse<String>> organizeAll() {
        service.organizeAll();
        return ResponseEntity.ok(ApiResponse.success("Organize completed"));
    }

    @PostMapping("/rescan")
    @Operation(summary = "一键刷新漫画库", description = "扫描所有根路径 → 整理文件夹 → 自动刮削未绑定漫画")
    public ResponseEntity<ApiResponse<String>> rescan() {
        for (String rootPath : getRootPaths()) {
            try {
                service.scanDirectory(rootPath);
            } catch (Exception e) {
                log.error("Failed to scan comic directory {}: {}", rootPath, e.getMessage());
            }
        }
        service.organizeAll();
        mangaScrapeService.autoScrapeAll();
        return ResponseEntity.ok(ApiResponse.success("Rescan started: scan → organize → scrape"));
    }

    @GetMapping("/bangumi/search")
    @Operation(summary = "搜索 Bangumi 漫画", description = "在 Bangumi 上搜索日漫（中文数据最全）")
    public ResponseEntity<ApiResponse<List<BangumiService.SearchResult>>> searchBangumi(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(mangaScrapeService.searchFromBangumi(q)));
    }

    @PostMapping("/{id:\\d+}/bangumi/bind")
    @Operation(summary = "绑定 Bangumi 元数据", description = "将指定 Bangumi 条目的元数据绑定到本地漫画。bindSeries=true 时同步系列级元数据到同系列所有卷")
    public ResponseEntity<ApiResponse<Comic>> bindBangumi(
            @Parameter(description = "漫画ID") @PathVariable Long id,
            @RequestBody ComicBindRequest request) {
        return ResponseEntity.ok(ApiResponse.success(mangaScrapeService.bindBangumi(id, request.getBangumiId(), request.isBindSeries())));
    }

    @GetMapping("/anilist/search")
    @Operation(summary = "搜索 AniList 漫画", description = "在 AniList 上搜索日漫（兜底源）")
    public ResponseEntity<ApiResponse<List<AnilistSearchResult.MediaItem>>> searchAnilist(
            @Parameter(description = "搜索关键词（漫画标题）") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(mangaScrapeService.searchFromAnilist(q)));
    }

    @PostMapping("/{id:\\d+}/anilist/bind")
    @Operation(summary = "绑定 AniList 元数据", description = "将指定 AniList 漫画的元数据绑定到本地漫画。bindSeries=true 时同步系列级元数据到同系列所有卷")
    public ResponseEntity<ApiResponse<Comic>> bindAnilist(
            @Parameter(description = "漫画ID") @PathVariable Long id,
            @RequestBody ComicBindRequest request) {
        return ResponseEntity.ok(ApiResponse.success(mangaScrapeService.bindAnilist(id, request.getAnilistId(), request.isBindSeries())));
    }

    @PostMapping("/auto-scrape")
    @Operation(summary = "自动刮削所有未绑定漫画", description = "Bangumi 优先，AniList 兜底")
    public ResponseEntity<ApiResponse<String>> autoScrape() {
        mangaScrapeService.autoScrapeAll();
        return ResponseEntity.ok(ApiResponse.success("Auto-scrape started"));
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