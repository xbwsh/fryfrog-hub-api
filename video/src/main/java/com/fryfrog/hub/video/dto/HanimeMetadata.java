package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Hanime 视频元数据 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Hanime 视频元数据")
public class HanimeMetadata {

    @Schema(description = "视频 ID")
    private String videoId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "简介")
    private String description;

    @Schema(description = "封面 URL")
    private String coverUrl;

    @Schema(description = "制作商")
    private String studio;

    @Schema(description = "视频类型")
    private String videoType;

    @Schema(description = "上传日期", example = "2024-01-15")
    private String uploadDate;

    @Schema(description = "观看次数")
    private Integer viewCount;

    @Schema(description = "标签列表")
    private List<String> tags;

    @Schema(description = "刮削时间戳")
    private Long scrapedAt;

    // ===== 视频播放地址相关字段 =====

    @Schema(description = "视频播放地址列表（不同分辨率）")
    private List<VideoSource> sources;

    @Schema(description = "默认播放地址（最高画质）")
    private String defaultUrl;

    @Schema(description = "页面地址")
    private String watchUrl;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "视频源")
    public static class VideoSource {
        @Schema(description = "分辨率", example = "1080p")
        private String resolution;

        @Schema(description = "视频格式", example = "mp4")
        private String format;

        @Schema(description = "视频 URL")
        private String url;

        @Schema(description = "文件大小（字节）")
        private Long fileSize;

        @Schema(description = "时长（秒）")
        private Integer duration;
    }
}
