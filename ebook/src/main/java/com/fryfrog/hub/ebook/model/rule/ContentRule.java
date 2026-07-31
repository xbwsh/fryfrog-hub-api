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
@Schema(description = "正文规则")
public class ContentRule {

    @Schema(description = "正文内容CSS选择器")
    @JsonProperty("content")
    private String content;

    @Schema(description = "下一页URL选择器")
    @JsonProperty("nextContentUrl")
    private String nextContentUrl;

    @Schema(description = "需要替换的内容正则")
    @JsonProperty("replaceRegex")
    private String replaceRegex;

    @Schema(description = "替换为的字符串")
    @JsonProperty("replaceTo")
    private String replaceTo;

    @Schema(description = "需要过滤的广告内容正则")
    @JsonProperty("filter")
    private String filter;

    @Schema(description = "图片URL选择器")
    @JsonProperty("image")
    private String image;

    @Schema(description = "图片懒加载属性")
    @JsonProperty("imageDecode")
    private String imageDecode;
}
