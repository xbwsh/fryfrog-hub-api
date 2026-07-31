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
@Schema(description = "书源规则")
public class BookSourceRule {

    @Schema(description = "书源名称")
    @JsonProperty("bookSourceName")
    private String bookSourceName;

    @Schema(description = "书源URL")
    @JsonProperty("bookSourceUrl")
    private String bookSourceUrl;

    @Schema(description = "书源分组")
    @JsonProperty("bookSourceGroup")
    private String bookSourceGroup;

    @Schema(description = "书源类型（0:书籍，1:漫画，2:有声）")
    @JsonProperty("bookSourceType")
    private Integer bookSourceType;

    @Schema(description = "书源作者")
    @JsonProperty("bookSourceAuthor")
    private String bookSourceAuthor;

    @Schema(description = "书源版本")
    @JsonProperty("bookSourceVersion")
    private String bookSourceVersion;

    @Schema(description = "书源说明")
    @JsonProperty("bookSourceComment")
    private String bookSourceComment;

    @Schema(description = "是否启用")
    @JsonProperty("enabled")
    private Boolean enabled;

    @Schema(description = "请求头JSON")
    @JsonProperty("header")
    private String header;

    @Schema(description = "搜索URL模板")
    @JsonProperty("searchUrl")
    private String searchUrl;

    @Schema(description = "搜索规则")
    @JsonProperty("ruleSearch")
    private SearchRule ruleSearch;

    @Schema(description = "书籍信息规则")
    @JsonProperty("ruleBookInfo")
    private BookInfoRule ruleBookInfo;

    @Schema(description = "目录规则")
    @JsonProperty("ruleToc")
    private TocRule ruleToc;

    @Schema(description = "正文规则")
    @JsonProperty("ruleContent")
    private ContentRule ruleContent;

    @Schema(description = "排序号")
    @JsonProperty("weight")
    private Integer weight;

    @Schema(description = "书源图标URL")
    @JsonProperty("bookSourceIcon")
    private String bookSourceIcon;

    @Schema(description = "自定义URL模板")
    @JsonProperty("bookUrlPattern")
    private String bookUrlPattern;
}
