package com.fryfrog.hub.ebook.dto;

import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "电子书列表条目（轻量）")
public class EbookListDTO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "格式: EPUB/PDF/MOBI")
    private String format;

    @Schema(description = "封面 URL（签名，可能为 null，前端用占位图）")
    private String coverUrl;

    @Schema(description = "是否读完")
    private Boolean completed;

    @Schema(description = "阅读进度百分比（0-100，未读为 null）")
    private Double progressPercent;

    public static EbookListDTO from(Ebook book, EbookProgress progress) {
        EbookListDTOBuilder builder = EbookListDTO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .series(book.getSeries())
                .format(book.getFormat())
                .coverUrl(book.getCoverUrl());
        if (progress != null) {
            builder.completed(progress.getCompleted())
                    .progressPercent(progress.getPositionPercent());
        }
        return builder.build();
    }
}
