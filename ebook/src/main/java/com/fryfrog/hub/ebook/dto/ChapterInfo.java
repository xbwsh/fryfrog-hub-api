package com.fryfrog.hub.ebook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "章节信息")
public class ChapterInfo {

    @Schema(description = "章节序号（从1开始）")
    private int chapterNum;

    @Schema(description = "章节标题")
    private String title;
}
