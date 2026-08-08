package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "视频元数据编辑请求（只更新传入的非空字段）")
public class VideoMetadataUpdateRequest {

    @Schema(description = "标题")
    private String title;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "评分", example = "8.5")
    private Double rating;

    @Schema(description = "年份", example = "2023")
    private Integer year;

    @Schema(description = "上映日期（精确到日）", example = "2023-01-22")
    private String releaseDate;

    @Schema(description = "类型（逗号分隔）", example = "科幻,剧情")
    private String genre;

    @Schema(description = "导演")
    private String director;

    @Schema(description = "演员（逗号分隔）")
    private String actors;

    @Schema(description = "原始标题")
    private String originalTitle;

    @Schema(description = "标签（逗号分隔）")
    private String tags;
}
