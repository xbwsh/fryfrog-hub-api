package com.fryfrog.hub.ebook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 电子书刮削数据（数据源无关的统一模型）。
 * provider 实现负责把外部源的字段映射到此处。
 */
@Data
@Schema(description = "电子书刮削候选/详情")
public class EbookScrapeResult {

    @Schema(description = "数据源 ID（bind 时回传）", example = "9585")
    private String sourceId;

    @Schema(description = "数据源标识", example = "bangumi")
    private String source;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "出版社")
    private String publisher;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "封面 URL")
    private String coverUrl;

    @Schema(description = "丛书名（可空）")
    private String series;

    @Schema(description = "丛书序号（可空）")
    private Integer seriesPart;

    @Schema(description = "出版年份（可空）")
    private Integer pubYear;

    @Schema(description = "评分（可空，0-5）")
    private Double rating;
}
