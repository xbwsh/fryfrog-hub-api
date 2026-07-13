package com.fryfrog.hub.comic.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "漫画系列信息")
public class ComicSeries {

    @Schema(description = "系列ID（关联 MediaSeries）")
    private Long seriesId;

    @Schema(description = "系列名称")
    private String name;

    @Schema(description = "封面URL")
    private String coverUrl;

    @Schema(description = "作者")
    private String author;

    @JsonIgnore
    private String coverArtPath;

    @Schema(description = "是否有本地封面文件")
    private Boolean hasCover;

    @Schema(description = "卷数")
    private Integer volumeCount;

    @Schema(description = "系列简介（总览）")
    private String seriesSummary;

    @Schema(description = "连载开始日期", example = "2019-03-25")
    private String serializationStart;

    @Schema(description = "该系列下的所有漫画")
    private List<ComicDTO> comics;
}
