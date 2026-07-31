package com.fryfrog.hub.ebook.model.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "搜索规则")
public class SearchRule {

    @Schema(description = "搜索结果列表CSS选择器")
    @JsonProperty("bookList")
    private String bookList;

    @Schema(description = "书名CSS选择器")
    @JsonProperty("name")
    private String name;

    @Schema(description = "作者CSS选择器")
    @JsonProperty("author")
    private String author;

    @Schema(description = "封面URL CSS选择器")
    @JsonProperty("coverUrl")
    private String coverUrl;

    @Schema(description = "详情页URL CSS选择器")
    @JsonProperty("bookUrl")
    private String bookUrl;

    @Schema(description = "简介CSS选择器")
    @JsonProperty("intro")
    private String intro;

    @Schema(description = "分类CSS选择器")
    @JsonProperty("kind")
    private String kind;

    @Schema(description = "最后更新时间CSS选择器")
    @JsonProperty("lastChapter")
    private String lastChapter;

    @Schema(description = "字数CSS选择器")
    @JsonProperty("wordCount")
    private String wordCount;
}
