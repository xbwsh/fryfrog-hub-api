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
}
