package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "视频 TMDB 绑定请求")
public class VideoBindRequest {

    @Schema(description = "TMDB ID", example = "545611", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tmdbId;

    @Schema(description = "媒体类型（movie/tv）", example = "movie", requiredMode = Schema.RequiredMode.REQUIRED)
    private String mediaType;
}
