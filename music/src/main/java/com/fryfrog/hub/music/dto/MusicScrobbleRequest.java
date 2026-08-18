package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登记播放请求")
public class MusicScrobbleRequest {

    @Schema(description = "单曲 ID")
    private Long songId;

    @Schema(description = "true=提交播放（累加次数），false=正在播放", example = "true")
    private Boolean submission;

    @Schema(description = "播放时间（毫秒时间戳）")
    private Long time;
}