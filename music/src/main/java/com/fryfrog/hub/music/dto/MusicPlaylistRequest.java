package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "创建播放列表请求")
public class MusicPlaylistRequest {

    @Schema(description = "播放列表名称", example = "开车专用")
    private String name;

    private String comment;

    @Schema(description = "是否公开", example = "false")
    private Boolean isPublic;

    @Schema(description = "初始曲目 ID")
    private List<Long> songIds;
}