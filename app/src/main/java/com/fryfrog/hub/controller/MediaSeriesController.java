package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.model.MediaSeries;
import com.fryfrog.hub.common.model.MediaSeriesCharacter;
import com.fryfrog.hub.common.repository.MediaSeriesCharacterRepository;
import com.fryfrog.hub.common.repository.MediaSeriesRepository;
import com.fryfrog.hub.comic.repository.ComicRepository;
import com.fryfrog.hub.comic.service.MangaScrapeService;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import com.fryfrog.hub.ebook.service.EbookBangumiScrapeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/media/series")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "媒体系列", description = "漫画/电子书系列管理")
public class MediaSeriesController {

    private final MediaSeriesRepository seriesRepo;
    private final MediaSeriesCharacterRepository characterRepo;
    private final ComicRepository comicRepo;
    private final EbookRepository ebookRepo;
    private final MangaScrapeService mangaScrapeService;
    private final EbookBangumiScrapeService ebookBangumiScrapeService;

    @GetMapping
    @Operation(summary = "获取系列列表", description = "返回所有系列，可按类型筛选")
    public ResponseEntity<ApiResponse<List<MediaSeries>>> getSeriesList(
            @Parameter(description = "媒体类型筛选") @RequestParam(required = false) String type) {
        List<MediaSeries> series;
        if (type != null && !type.isBlank()) {
            series = seriesRepo.findByMediaType(type);
        } else {
            series = seriesRepo.findAll();
        }
        return ResponseEntity.ok(ApiResponse.success(series));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取系列详情", description = "返回系列信息，含该系列下的所有漫画/电子书")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSeriesDetail(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        MediaSeries series = seriesRepo.findById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.ok(ApiResponse.error("Series not found: " + id));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("series", series);

        // 查找该系列下的漫画
        var comics = comicRepo.findBySeriesRef_Id(series.getId());
        if (!comics.isEmpty()) {
            result.put("comics", comics);
        }

        // 查找该系列下的电子书
        var ebooks = ebookRepo.findBySeriesRef_Id(series.getId());
        if (!ebooks.isEmpty()) {
            result.put("ebooks", ebooks);
        }

        // 查找角色
        List<MediaSeriesCharacter> characters = characterRepo.findBySeries_Id(id);
        if (!characters.isEmpty()) {
            result.put("characters", characters);
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{id}/cover")
    @Operation(summary = "获取系列封面", description = "返回系列封面图片")
    public ResponseEntity<Resource> getSeriesCover(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        MediaSeries series = seriesRepo.findById(id).orElse(null);
        if (series == null || series.getCoverArtPath() == null) {
            return ResponseEntity.notFound().build();
        }

        File coverFile = new File(series.getCoverArtPath());
        if (!coverFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(coverFile));
    }

    @GetMapping("/{id}/characters")
    @Operation(summary = "获取系列角色", description = "返回该系列的所有角色")
    public ResponseEntity<ApiResponse<List<MediaSeriesCharacter>>> getSeriesCharacters(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        List<MediaSeriesCharacter> characters = characterRepo.findBySeries_Id(id);
        return ResponseEntity.ok(ApiResponse.success(characters));
    }

    @GetMapping("/character/{id}/image")
    @Operation(summary = "获取角色图片", description = "返回角色图片")
    public ResponseEntity<Resource> getCharacterImage(
            @Parameter(description = "角色ID") @PathVariable Long id) {
        MediaSeriesCharacter character = characterRepo.findById(id).orElse(null);
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

    @PutMapping("/{id}/favorite")
    @Operation(summary = "切换系列收藏", description = "切换系列的收藏状态")
    public ResponseEntity<ApiResponse<MediaSeries>> toggleFavorite(
            @Parameter(description = "系列ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        MediaSeries series = seriesRepo.findById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.ok(ApiResponse.error("Series not found: " + id));
        }
        series.setFavorite(status);
        seriesRepo.save(series);
        return ResponseEntity.ok(ApiResponse.success(series));
    }

    @PostMapping("/{id}/rescrape")
    @Operation(summary = "重新刮削系列", description = "重新获取系列简介、封面、每个卷的封面和简介")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rescrapeSeries(
            @Parameter(description = "系列ID") @PathVariable Long id) {
        MediaSeries series = seriesRepo.findById(id).orElse(null);
        if (series == null) {
            return ResponseEntity.ok(ApiResponse.error("Series not found: " + id));
        }

        int updated = 0;
        if ("comic".equals(series.getMediaType()) || "both".equals(series.getMediaType())) {
            updated += mangaScrapeService.rescrapeSeries(series.getTitle());
        }
        if ("ebook".equals(series.getMediaType()) || "both".equals(series.getMediaType())) {
            updated += ebookBangumiScrapeService.rescrapeSeries(series.getTitle());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seriesId", series.getId());
        result.put("seriesName", series.getTitle());
        result.put("updated", updated);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
