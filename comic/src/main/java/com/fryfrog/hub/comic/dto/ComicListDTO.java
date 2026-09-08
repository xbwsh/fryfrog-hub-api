package com.fryfrog.hub.comic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "漫画列表条目（轻量）")
public class ComicListDTO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "卷/话数")
    private Integer totalChapters;

    @Schema(description = "封面 URL（签名，可能为 null，前端用占位图）")
    private String coverUrl;

    @Schema(description = "是否读完")
    private Boolean completed;

    @Schema(description = "阅读进度百分比（0-100，未读为 null）")
    private Double progressPercent;

    public static ComicListDTO from(com.fryfrog.hub.comic.model.Comic comic,
                                    com.fryfrog.hub.comic.model.ComicProgress progress,
                                    Double progressPercent) {
        ComicListDTOBuilder builder = ComicListDTO.builder()
                .id(comic.getId())
                .title(comic.getTitle())
                .author(comic.getAuthor())
                .series(comic.getSeries())
                .totalChapters(comic.getTotalChapters())
                .coverUrl(comic.getCoverUrl());
        if (progress != null) {
            builder.completed(progress.getCompleted())
                    .progressPercent(progressPercent);
        }
        return builder.build();
    }
}
