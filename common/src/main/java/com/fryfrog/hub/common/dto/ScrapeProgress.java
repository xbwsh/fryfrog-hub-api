package com.fryfrog.hub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "刮削进度信息")
public class ScrapeProgress {

    @Schema(description = "模块名称", example = "comic")
    private String module;

    @Schema(description = "是否正在刮削")
    private boolean running;

    @Schema(description = "总数", example = "10")
    private int total;

    @Schema(description = "已完成数", example = "3")
    private int completed;

    @Schema(description = "失败数", example = "1")
    private int failed;

    @Schema(description = "跳过数", example = "2")
    private int skipped;

    @Schema(description = "开始时间")
    private LocalDateTime startedAt;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "当前处理项名称", example = "间谍过家家 Vol.01")
    private String currentItem;

    @Schema(description = "各条目详情")
    private List<ScrapeItemStatus> items = new ArrayList<>();

    public int getPending() {
        return total - completed - failed - skipped;
    }

    public double getPercent() {
        if (total == 0) return 0;
        return Math.round((double) completed / total * 1000) / 10.0;
    }

    @Data
    @Schema(description = "单条刮削状态")
    public static class ScrapeItemStatus {
        @Schema(description = "媒体名称")
        private String name;

        @Schema(description = "状态: pending/processing/completed/failed/skipped")
        private String status;

        @Schema(description = "失败原因")
        private String error;

        @Schema(description = "处理时间")
        private LocalDateTime processedAt;
    }
}
