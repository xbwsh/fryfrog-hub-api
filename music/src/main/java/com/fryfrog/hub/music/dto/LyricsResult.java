package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "歌词搜索结果")
public class LyricsResult {

    @Schema(description = "艺术家")
    private String artist;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "歌词文本")
    private String lyrics;

    @Schema(description = "语言")
    private String language;
}