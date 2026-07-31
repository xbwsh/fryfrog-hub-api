package com.fryfrog.hub.ebook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "在线书籍搜索结果")
public class OnlineBookDTO {

    @Schema(description = "书名")
    private String name;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "封面URL")
    private String coverUrl;

    @Schema(description = "书籍详情页URL")
    private String bookUrl;

    @Schema(description = "简介")
    private String intro;

    @Schema(description = "分类")
    private String kind;

    @Schema(description = "最新章节")
    private String lastChapter;

    @Schema(description = "字数")
    private String wordCount;

    @Schema(description = "书源ID")
    private Long sourceId;

    @Schema(description = "书源名称")
    private String sourceName;
}
