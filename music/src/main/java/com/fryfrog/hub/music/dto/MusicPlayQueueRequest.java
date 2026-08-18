package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "保存播放队列请求")
public class MusicPlayQueueRequest {

    @Schema(description = "队列曲目 ID（按顺序）")
    private List<Long> songIds;

    @Schema(description = "当前播放曲目 ID")
    private Long currentSongId;

    @Schema(description = "当前播放位置（秒）")
    private Double positionSeconds;
}