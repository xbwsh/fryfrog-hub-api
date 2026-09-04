package com.fryfrog.hub.audiobook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 有声书刮削数据（数据源无关的统一模型）。
 * provider 实现负责把外部源的字段映射到此处。
 */
@Data
@Schema(description = "有声书刮削候选/详情")
public class AudiobookScrapeResult {

    @Schema(description = "数据源 ID（bind 时回传）", example = "1442892346")
    private String sourceId;

    @Schema(description = "数据源标识", example = "itunes")
    private String source;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "朗读者")
    private String narrator;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "封面 URL")
    private String coverUrl;

    @Schema(description = "系列名（可空）")
    private String series;

    @Schema(description = "系列序号（可空）")
    private Integer seriesPart;

    @Schema(description = "发布年份（可空）")
    private Integer year;

    @Schema(description = "评分（可空，0-5）")
    private Double rating;
}
