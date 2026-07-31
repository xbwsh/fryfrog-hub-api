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
@Schema(description = "目录规则")
public class TocRule {

    @Schema(description = "章节列表CSS选择器")
    @JsonProperty("chapterList")
    private String chapterList;

    @Schema(description = "章节名CSS选择器")
    @JsonProperty("chapterName")
    private String chapterName;

    @Schema(description = "章节URL CSS选择器")
    @JsonProperty("chapterUrl")
    private String chapterUrl;

    @Schema(description = "是否反转章节列表")
    @JsonProperty("isVolume")
    private Boolean isVolume;

    @Schema(description = "下一页URL选择器")
    @JsonProperty("nextTocUrl")
    private String nextTocUrl;
}
