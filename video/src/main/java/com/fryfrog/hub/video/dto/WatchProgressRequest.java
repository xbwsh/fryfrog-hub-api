package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "观看进度保存请求")
public class WatchProgressRequest {

    @Schema(description = "播放位置（秒）", example = "3600.5")
    private Double position;

    @Schema(description = "视频总时长（秒）", example = "7200.0")
    private Double duration;
}
