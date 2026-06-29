package com.fryfrog.hub.common.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

@RestController
@RequestMapping("/api/v1/media-libraries")
@RequiredArgsConstructor
@Tag(name = "媒体资源库管理", description = "管理视频/音乐等媒体资源目录")
public class MediaLibraryController {

    private final MediaLibraryService service;

    @GetMapping
    @Operation(summary = "获取所有资源库")
    public ResponseEntity<ApiResponse<List<MediaLibrary>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllLibraries()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取资源库详情")
    public ResponseEntity<ApiResponse<MediaLibrary>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getLibraryById(id)));
    }

    @PostMapping
    @Operation(summary = "创建资源库")
    public ResponseEntity<ApiResponse<MediaLibrary>> create(@RequestBody MediaLibrary library) {
        return ResponseEntity.ok(ApiResponse.success(service.createLibrary(library)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新资源库")
    public ResponseEntity<ApiResponse<MediaLibrary>> update(
            @PathVariable Long id,
            @RequestBody MediaLibrary library) {
        return ResponseEntity.ok(ApiResponse.success(service.updateLibrary(id, library)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除资源库")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        service.deleteLibrary(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", id)));
    }

    @PutMapping("/{id}/toggle")
    @Operation(summary = "启用/禁用资源库")
    public ResponseEntity<ApiResponse<MediaLibrary>> toggle(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.toggleLibrary(id)));
    }

    @GetMapping("/browse")
    @Operation(summary = "浏览服务器目录", description = "列出指定路径下的子目录，用于前端目录选择器")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> browse(
            @Parameter(description = "目录路径，不传则返回根目录列表")
            @RequestParam(required = false) String path) {
        File dir;
        if (path == null || path.isBlank()) {
            dir = File.listRoots()[0];
        } else {
            dir = new File(path);
        }

        if (!dir.exists() || !dir.isDirectory()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }

        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (File child : children) {
            if (child.isHidden()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", child.getName());
            item.put("path", child.getAbsolutePath());
            item.put("writable", child.canWrite());
            result.add(item);
        }

        result.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
