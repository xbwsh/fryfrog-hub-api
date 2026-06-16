package com.fryfrog.hub.comic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "漫画搜索结果")
public class ComicSearchResult {

    @Schema(description = "外部平台ID")
    private String id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "系列名称")
    private String series;

    @Schema(description = "卷号")
    private Integer volume;

    @Schema(description = "年份")
    private Integer year;

    @Schema(description = "类型/标签")
    private String genre;

    @Schema(description = "摘要描述")
    private String description;

    @Schema(description = "封面图片URL")
    private String coverUrl;

    @Schema(description = "数据来源平台")
    private String platform;

    @Schema(description = "评分")
    private Double rating;
}
