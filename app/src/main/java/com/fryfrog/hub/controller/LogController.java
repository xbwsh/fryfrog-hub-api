package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/logs")
@Tag(name = "日志导出", description = "导出日志文件，方便反馈开发者排查问题")
public class LogController {

    @Value("${LOG_DIR:./logs}")
    private String logDir;

    private static final Set<String> ALLOWED_FILES = Set.of(
            "app.log", "music.log", "comic.log", "ebook.log", "video.log"
    );

    @GetMapping
    @Operation(summary = "列出可用的日志文件")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listLogs() {
        Path dir = Path.of(logDir);
        List<Map<String, Object>> result = new ArrayList<>();

        if (!Files.isDirectory(dir)) {
            return ResponseEntity.ok(ApiResponse.success(result));
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".log") && ALLOWED_FILES.contains(name);
            }).sorted().forEach(p -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", p.getFileName().toString());
                try {
                    info.put("size", Files.size(p));
                    info.put("lastModified", Files.getLastModifiedTime(p).toMillis());
                } catch (IOException e) {
                    info.put("size", 0);
                    info.put("lastModified", 0);
                }
                result.add(info);
            });
        } catch (IOException ignored) {
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{fileName}")
    @Operation(summary = "导出日志文件", description = "下载指定日志文件，用于反馈给开发者排查问题")
    public ResponseEntity<Resource> exportLog(
            @Parameter(description = "日志文件名") @PathVariable String fileName) {
        if (!ALLOWED_FILES.contains(fileName)) {
            return ResponseEntity.badRequest().build();
        }

        File file = Path.of(logDir, fileName).toFile();
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentLength(file.length())
                .body(resource);
    }
}
