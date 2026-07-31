package com.fryfrog.hub.ebook.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.ebook.dto.OnlineBookDTO;
import com.fryfrog.hub.ebook.dto.OnlineChapterDTO;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.service.OnlineBookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ebook/online")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "在线书籍", description = "在线书源搜索、阅读接口")
public class OnlineBookController {

    private final OnlineBookService onlineBookService;

    @GetMapping("/search")
    @Operation(summary = "搜索在线书籍", description = "从书源搜索书籍，可指定书源ID")
    public ResponseEntity<ApiResponse<List<OnlineBookDTO>>> search(
            @Parameter(description = "搜索关键词") @RequestParam String keyword,
            @Parameter(description = "书源ID（可选，为空则搜索所有启用的书源）") @RequestParam(required = false) Long sourceId) {
        log.info("搜索在线书籍: keyword={}, sourceId={}", keyword, sourceId);
        return ResponseEntity.ok(ApiResponse.success(onlineBookService.search(keyword, sourceId)));
    }

    @GetMapping("/chapters")
    @Operation(summary = "获取章节目录", description = "获取在线书籍的章节目录")
    public ResponseEntity<ApiResponse<List<OnlineChapterDTO>>> getChapters(
            @Parameter(description = "书籍详情页URL") @RequestParam String bookUrl,
            @Parameter(description = "书源ID") @RequestParam Long sourceId) {
        log.info("获取章节目录: bookUrl={}, sourceId={}", bookUrl, sourceId);
        return ResponseEntity.ok(ApiResponse.success(onlineBookService.getChapters(bookUrl, sourceId)));
    }

    @GetMapping("/chapter")
    @Operation(summary = "获取章节内容", description = "获取指定章节的正文内容")
    public ResponseEntity<ApiResponse<String>> getChapterContent(
            @Parameter(description = "章节URL") @RequestParam String chapterUrl,
            @Parameter(description = "书源ID") @RequestParam Long sourceId) {
        log.info("获取章节内容: chapterUrl={}, sourceId={}", chapterUrl, sourceId);
        return ResponseEntity.ok(ApiResponse.success(onlineBookService.getChapterContent(chapterUrl, sourceId)));
    }

    @PostMapping("/add-to-shelf")
    @Operation(summary = "加入书架", description = "将在线书籍加入书架")
    public ResponseEntity<ApiResponse<Ebook>> addToShelf(
            @RequestBody Map<String, Object> request) {
        String bookUrl = (String) request.get("bookUrl");
        Long sourceId = request.get("sourceId") != null ? 
                Long.valueOf(request.get("sourceId").toString()) : null;

        @SuppressWarnings("unchecked")
        Map<String, String> bookInfo = (Map<String, String>) request.get("bookInfo");

        log.info("将在线书籍加入书架: bookUrl={}, sourceId={}", bookUrl, sourceId);
        return ResponseEntity.ok(ApiResponse.success(
                onlineBookService.addToShelf(bookUrl, sourceId, bookInfo)));
    }
}
