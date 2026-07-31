package com.fryfrog.hub.ebook.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.ebook.dto.BookSourceDTO;
import com.fryfrog.hub.ebook.service.BookSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/book-sources")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "书源管理", description = "书源的增删改查、导入导出")
public class BookSourceController {

    private final BookSourceService service;

    @GetMapping
    @Operation(summary = "获取所有书源", description = "返回所有未删除的书源列表")
    public ResponseEntity<ApiResponse<List<BookSourceDTO>>> findAll() {
        return ResponseEntity.ok(ApiResponse.success(service.findAll()));
    }

    @GetMapping("/enabled")
    @Operation(summary = "获取启用的书源", description = "返回所有启用且未删除的书源列表")
    public ResponseEntity<ApiResponse<List<BookSourceDTO>>> findEnabled() {
        return ResponseEntity.ok(ApiResponse.success(service.findEnabled()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取书源详情", description = "根据ID获取书源详情")
    public ResponseEntity<ApiResponse<BookSourceDTO>> findById(
            @Parameter(description = "书源ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.findById(id)));
    }

    @PostMapping
    @Operation(summary = "新增书源", description = "创建新的书源配置")
    public ResponseEntity<ApiResponse<BookSourceDTO>> create(@RequestBody BookSourceDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(service.create(dto)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新书源", description = "更新指定书源的配置")
    public ResponseEntity<ApiResponse<BookSourceDTO>> update(
            @Parameter(description = "书源ID") @PathVariable Long id,
            @RequestBody BookSourceDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, dto)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除书源", description = "软删除指定书源")
    public ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(description = "书源ID") @PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}/toggle")
    @Operation(summary = "启用/禁用书源", description = "切换书源的启用状态")
    public ResponseEntity<ApiResponse<BookSourceDTO>> toggleEnabled(
            @Parameter(description = "书源ID") @PathVariable Long id,
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResponse.success(service.toggleEnabled(id, enabled)));
    }

    @PostMapping("/import")
    @Operation(summary = "从URL导入书源", description = "从远程URL导入书源JSON配置")
    public ResponseEntity<ApiResponse<List<BookSourceDTO>>> importFromUrl(
            @Parameter(description = "书源JSON的URL") @RequestParam String url) {
        log.info("从URL导入书源: {}", url);
        return ResponseEntity.ok(ApiResponse.success(service.importFromUrl(url)));
    }

    @PostMapping(value = "/import/json", consumes = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "从JSON导入书源", description = "从JSON字符串导入书源配置")
    public ResponseEntity<ApiResponse<List<BookSourceDTO>>> importFromJson(
            @RequestBody String json) {
        log.info("从JSON导入书源");
        return ResponseEntity.ok(ApiResponse.success(service.importFromJson(json)));
    }

    @GetMapping("/{id}/export")
    @Operation(summary = "导出书源", description = "导出指定书源的JSON配置")
    public ResponseEntity<ApiResponse<String>> exportToJson(
            @Parameter(description = "书源ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.exportToJson(id)));
    }

    @GetMapping("/groups")
    @Operation(summary = "获取书源分组列表", description = "返回所有书源分组名称")
    public ResponseEntity<ApiResponse<List<String>>> findGroups() {
        return ResponseEntity.ok(ApiResponse.success(service.findDistinctGroups()));
    }

    @GetMapping("/stats")
    @Operation(summary = "获取书源统计", description = "返回书源数量统计")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getStats() {
        Map<String, Long> stats = Map.of(
                "total", (long) service.findAll().size(),
                "enabled", service.countEnabled()
        );
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
