package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建书签请求")
public class MusicBookmarkRequest {

    @Schema(description = "单曲 ID")
    private Long songId;

    @Schema(description = "书签位置（秒）")
    private Double positionSeconds;

    private String comment;
}