package com.fryfrog.hub.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "系列元数据编辑请求（只更新传入的非空字段）")
public class SeriesMetadataUpdateRequest {

    @Schema(description = "标题")
    private String title;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "评分", example = "8.5")
    private Double rating;

    @Schema(description = "年份", example = "2022")
    private Integer year;

    @Schema(description = "上映日期（精确到日）", example = "2022-04-09")
    private String releaseDate;

    @Schema(description = "原始标题")
    private String originalTitle;

    @Schema(description = "播出状态", example = "Ended")
    private String status;
}
