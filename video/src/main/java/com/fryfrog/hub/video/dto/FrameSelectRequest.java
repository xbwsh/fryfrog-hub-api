package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "选择截帧作为封面请求")
public class FrameSelectRequest {

    @Schema(description = "候选帧索引（0-5）", example = "2")
    private int index;

    @Schema(description = "封面类型: poster=竖屏封面, fanart=横屏背景图", example = "poster")
    private String type;
}
