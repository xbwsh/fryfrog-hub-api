package com.fryfrog.hub.ebook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "电子书系列信息")
public class EbookSeries {

    @Schema(description = "系列ID（关联 MediaSeries）")
    private Long seriesId;

    @Schema(description = "系列名称")
    private String name;

    @Schema(description = "作者")
    private String author;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String coverArtPath;

    @Schema(description = "是否有本地封面文件")
    private Boolean hasCover;

    @Schema(description = "卷数")
    private Integer volumeCount;

    @Schema(description = "系列简介（总览）")
    private String seriesSummary;

    @Schema(description = "该系列下的所有电子书")
    private List<EbookDTO> books;

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        if (!Boolean.TRUE.equals(hasCover)) return null;
        try {
            return "/api/v1/ebook/series/cover?series=" + java.net.URLEncoder.encode(name, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
