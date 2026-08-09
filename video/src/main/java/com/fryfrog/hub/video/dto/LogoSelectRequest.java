package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户选择某个字标 logo 时的请求体。
 */
@Data
@Schema(description = "设置字标Logo请求")
public class LogoSelectRequest {

    @Schema(description = "TMDB 图片路径（来自 logo-options 返回的 filePath）", example = "/g2Zv6Xg9PRS5ih207vUAkU1LGqU.png")
    private String filePath;
}
