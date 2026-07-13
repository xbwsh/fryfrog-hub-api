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

import java.util.List;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "系统设置", description = "运行时配置管理")
public class SettingController {

    private final SystemSettingService settingService;
    private final PeriodicScanScheduler scanScheduler;

    public SettingController(SystemSettingService settingService, PeriodicScanScheduler scanScheduler) {
        this.settingService = settingService;
        this.scanScheduler = scanScheduler;
    }

    @GetMapping
    @Operation(summary = "获取所有设置", description = "返回所有设置")
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

        if (key.equals("watcher.periodic-scan-interval")) {
            try {
                int interval = Integer.parseInt(request.getValue());
                scanScheduler.updateInterval(interval);
            } catch (NumberFormatException ignored) {}
        }

        return ResponseEntity.ok(ApiResponse.success("设置已更新", setting));
    }

}
