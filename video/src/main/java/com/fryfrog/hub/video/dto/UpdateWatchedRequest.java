package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "设置已观看状态请求")
public class UpdateWatchedRequest {

    @Schema(description = "是否已看完", example = "true")
    private Boolean completed;
}
