package com.fryfrog.hub.comic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "漫画刮削候选/详情")
public class ComicScrapeResult {

    @Schema(description = "数据源 ID（bind 时回传）", example = "25581")
    private String sourceId;

    @Schema(description = "数据源标识", example = "bangumi")
    private String source;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "丛书名（可空）")
    private String series;

    @Schema(description = "丛书序号（可空）")
    private Integer seriesPart;

    @Schema(description = "出版年份（可空）")
    private Integer pubYear;

    @Schema(description = "评分（可空，0-5）")
    private Double rating;

    @Schema(description = "封面 URL")
    private String coverUrl;
}
