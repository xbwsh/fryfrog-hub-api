package com.fryfrog.hub.ebook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "图书搜索结果（聚合API统一格式）")
public class BookSearchResult {

    @Schema(description = "书籍ID（来源平台）")
    private String id;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "出版社")
    private String publisher;

    @Schema(description = "ISBN")
    private String isbn;

    @Schema(description = "出版年份")
    private Integer year;

    @Schema(description = "类型/分类")
    private String genre;

    @Schema(description = "简介")
    private String description;

    @Schema(description = "封面图片URL")
    private String coverUrl;

    @Schema(description = "数据来源平台", example = "douban")
    private String platform;

    @Schema(description = "豆瓣评分")
    private Double rating;

    @Schema(description = "页数")
    private Integer pageCount;

    @Schema(description = "语言")
    private String language;
}