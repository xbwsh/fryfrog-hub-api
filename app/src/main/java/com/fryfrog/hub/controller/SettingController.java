package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.SettingUpdateRequest;
import com.fryfrog.hub.common.model.SystemSetting;
import com.fryfrog.hub.common.service.SystemSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "系统设置", description = "运行时配置管理（TMDB、代理、刮削等）")
public class SettingController {

    private final SystemSettingService settingService;

    public SettingController(SystemSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping
    @Operation(summary = "获取所有设置")
    public ResponseEntity<ApiResponse<List<SystemSetting>>> getAllSettings() {
        return ResponseEntity.ok(ApiResponse.success(settingService.getAll()));
    }

    @GetMapping("/{key}")
    @Operation(summary = "获取单个设置")
    public ResponseEntity<ApiResponse<SystemSetting>> getSetting(@PathVariable String key) {
        return settingService.getByKey(key)
                .map(s -> ResponseEntity.ok(ApiResponse.success(s)))
                .orElse(ResponseEntity.ok(ApiResponse.error("Setting not found: " + key)));
    }

    @PutMapping("/{key}")
    @Operation(summary = "更新设置")
    public ResponseEntity<ApiResponse<SystemSetting>> updateSetting(
            @PathVariable String key,
            @RequestBody SettingUpdateRequest request) {
        SystemSetting setting = settingService.setValue(key, request.getValue(), null);
        return ResponseEntity.ok(ApiResponse.success("设置已更新", setting));
    }

    @GetMapping("/tmdb/status")
    @Operation(summary = "检查 TMDB 配置状态")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTmdbStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        String apiKey = settingService.getValue("tmdb.api-key", "");
        status.put("configured", !apiKey.isBlank());
        status.put("language", settingService.getValue("tmdb.language", "zh-CN"));
        status.put("image-size", settingService.getValue("tmdb.image-size", "original"));
        status.put("auto-scrape", settingService.getBoolean("tmdb.auto-scrape", false));
        status.put("include-adult", settingService.getBoolean("tmdb.include-adult", true));
        return ResponseEntity.ok(ApiResponse.success(status));
    }
}
