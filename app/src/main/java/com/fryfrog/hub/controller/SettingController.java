package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.SettingUpdateRequest;
import com.fryfrog.hub.common.model.SystemSetting;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import com.fryfrog.hub.common.service.SystemSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "系统设置", description = "运行时配置管理（TMDB、代理、刮削等）")
public class SettingController {

    private final SystemSettingService settingService;
    private final PeriodicScanScheduler scanScheduler;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "tmdb.api-key",
            "hub.tmdb.api-key"
    );

    public SettingController(SystemSettingService settingService, PeriodicScanScheduler scanScheduler) {
        this.settingService = settingService;
        this.scanScheduler = scanScheduler;
    }

    @GetMapping
    @Operation(summary = "获取所有设置", description = "返回所有设置，敏感字段（如api-key）只返回是否配置")
    public ResponseEntity<ApiResponse<List<SystemSetting>>> getAllSettings() {
        List<SystemSetting> settings = settingService.getAll().stream()
                .map(s -> {
                    SystemSetting dto = new SystemSetting();
                    dto.setId(s.getId());
                    String displayKey = s.getKey().startsWith("hub.") ? s.getKey().substring(4) : s.getKey();
                    dto.setKey(displayKey);

                    if (SENSITIVE_KEYS.contains(displayKey) || SENSITIVE_KEYS.contains(s.getKey())) {
                        dto.setValue(s.getValue().isBlank() ? "" : "***已配置***");
                    } else {
                        dto.setValue(s.getValue());
                    }

                    dto.setDescription(s.getDescription());
                    return dto;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(settings));
    }

    @GetMapping("/{key}")
    @Operation(summary = "获取单个设置", description = "获取设置值，敏感字段只返回是否配置")
    public ResponseEntity<ApiResponse<SystemSetting>> getSetting(@PathVariable String key) {
        String dbKey = key.startsWith("hub.") ? key : "hub." + key;
        return settingService.getByKey(dbKey)
                .map(s -> {
                    SystemSetting dto = new SystemSetting();
                    dto.setId(s.getId());
                    dto.setKey(key);

                    if (SENSITIVE_KEYS.contains(key) || SENSITIVE_KEYS.contains(s.getKey())) {
                        dto.setValue(s.getValue().isBlank() ? "" : "***已配置***");
                    } else {
                        dto.setValue(s.getValue());
                    }

                    dto.setDescription(s.getDescription());
                    return ResponseEntity.ok(ApiResponse.success(dto));
                })
                .orElse(ResponseEntity.ok(ApiResponse.error("Setting not found: " + key)));
    }

    @PutMapping("/{key}")
    @Operation(summary = "更新设置")
    public ResponseEntity<ApiResponse<SystemSetting>> updateSetting(
            @PathVariable String key,
            @RequestBody SettingUpdateRequest request) {
        String dbKey = key.startsWith("hub.") ? key : "hub." + key;
        SystemSetting setting = settingService.setValue(dbKey, request.getValue(), null);

        if (dbKey.endsWith("watcher.periodic-scan-interval")) {
            try {
                int interval = Integer.parseInt(request.getValue());
                scanScheduler.updateInterval(interval);
            } catch (NumberFormatException ignored) {}
        }

        String displayKey = setting.getKey().startsWith("hub.") ? setting.getKey().substring(4) : setting.getKey();
        setting.setKey(displayKey);

        if (SENSITIVE_KEYS.contains(key) || SENSITIVE_KEYS.contains(dbKey)) {
            setting.setValue("***已配置***");
        }

        return ResponseEntity.ok(ApiResponse.success("设置已更新", setting));
    }

    @GetMapping("/tmdb/status")
    @Operation(summary = "检查 TMDB 配置状态")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTmdbStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        String apiKey = settingService.getValue("hub.tmdb.api-key", "");
        status.put("configured", !apiKey.isBlank());
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @GetMapping("/performance")
    @Operation(summary = "获取性能相关设置")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPerformanceSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("watcher.periodic-scan", settingService.getBoolean("watcher.periodic-scan", true));
        settings.put("watcher.periodic-scan-interval", settingService.getInteger("watcher.periodic-scan-interval", 300));
        return ResponseEntity.ok(ApiResponse.success(settings));
    }
}
