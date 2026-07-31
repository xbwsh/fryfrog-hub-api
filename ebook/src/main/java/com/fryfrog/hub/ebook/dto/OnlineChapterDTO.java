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
@Schema(description = "在线书籍章节")
public class OnlineChapterDTO {

    @Schema(description = "章节序号")
    private Integer chapterNum;

    @Schema(description = "章节名称")
    private String chapterName;

    @Schema(description = "章节URL")
    private String chapterUrl;

    @Schema(description = "是否已缓存")
    private Boolean cached;
}
