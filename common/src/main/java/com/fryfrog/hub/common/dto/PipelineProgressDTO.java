package com.fryfrog.hub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "媒体库流水线聚合进度（扫描+刮削+资产生成）")
public class PipelineProgressDTO {

    @Schema(description = "资源库ID")
    private Long libraryId;

    @Schema(description = "当前阶段: scan=扫描, scrape=刮削, actors=演员, assets=资产生成, done=完成", example = "scrape")
    private String stage;

    @Schema(description = "是否正在运行")
    private boolean running;

    @Schema(description = "整体进度百分比 0-100", example = "45.0")
    private Double percent;

    @Schema(description = "当前处理项名称", example = "漆黑的射干")
    private String currentItem;

    @Schema(description = "是否启用刮削（false 时降级为仅扫描）", example = "true")
    private Boolean scrapingEnabled;

    @Schema(description = "扫描进度（0-100）", example = "100.0")
    private Double scanPercent;

    @Schema(description = "刮削进度（0-100）", example = "40.0")
    private Double scrapePercent;

    public static PipelineProgressDTO of(Long libraryId,
                                         ScrapeProgress pipeline,
                                         ScrapeProgress scan,
                                         ScrapeProgress scrape,
                                         boolean scrapingEnabled) {
        PipelineProgressDTO dto = new PipelineProgressDTO();
        dto.setLibraryId(libraryId);
        dto.setStage(pipeline.getStage() != null ? pipeline.getStage() : "idle");
        dto.setRunning(pipeline.isRunning());
        dto.setScrapingEnabled(scrapingEnabled);
        dto.setScanPercent(scan.getPercent());
        dto.setScrapePercent(scrape.getPercent());

        // 整体进度：扫描 30% + 刮削 40% + 资产生成 30%（粗略权重）
        double percent = switch (dto.getStage()) {
            case "scan" -> scan.getPercent() * 0.3;
            case "scrape" -> 30 + scrape.getPercent() * 0.4;
            case "actors", "assets" -> 70;
            case "done" -> 100.0;
            default -> 0.0;
        };
        dto.setPercent(Math.round(percent * 10) / 10.0);

        String current = switch (dto.getStage()) {
            case "scan" -> scan.getCurrentItem();
            case "scrape" -> scrape.getCurrentItem();
            default -> null;
        };
        dto.setCurrentItem(current);
        return dto;
    }
}
