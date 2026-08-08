package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "从单集截帧设置系列横屏背景图请求")
public class SeriesFrameSelectRequest {

    @Schema(description = "单集视频ID（用于生成候选帧的视频）", example = "123")
    private Long videoId;

    @Schema(description = "候选帧索引（0-5）", example = "2")
    private int index;
}
