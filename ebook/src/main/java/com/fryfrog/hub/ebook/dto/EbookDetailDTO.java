package com.fryfrog.hub.ebook.dto;

import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "电子书详情")
public class EbookDetailDTO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "出版社")
    private String publisher;

    @Schema(description = "语言")
    private String language;

    @Schema(description = "出版年份")
    private Integer pubYear;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "丛书序号")
    private Integer seriesPart;

    @Schema(description = "元数据来源: manual/scrape/scan")
    private String metadataSource;

    @Schema(description = "格式: EPUB/PDF/MOBI")
    private String format;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "章节数")
    private Integer totalChapters;

    @Schema(description = "封面 URL（签名）")
    private String coverUrl;

    @Schema(description = "原文件下载 URL（签名）")
    private String fileUrl;

    @Schema(description = "当前用户阅读进度")
    private ProgressDTO progress;

    @Builder
    @Data
    @Schema(description = "阅读进度")
    public static class ProgressDTO {

        @Schema(description = "阅读进度百分比（0-100）")
        private Double positionPercent;

        @Schema(description = "当前章节索引")
        private Integer chapterIndex;

        @Schema(description = "是否读完")
        private Boolean completed;

        public static ProgressDTO from(EbookProgress progress) {
            return ProgressDTO.builder()
                    .positionPercent(progress.getPositionPercent())
                    .chapterIndex(progress.getChapterIndex())
                    .completed(progress.getCompleted())
                    .build();
        }
    }

    public static EbookDetailDTO from(Ebook book, EbookProgress progress) {
        return EbookDetailDTO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .publisher(book.getPublisher())
                .language(book.getLanguage())
                .pubYear(book.getPubYear())
                .overview(book.getOverview())
                .series(book.getSeries())
                .seriesPart(book.getSeriesPart())
                .metadataSource(book.getMetadataSource())
                .format(book.getFormat())
                .fileSize(book.getFileSize())
                .totalChapters(book.getTotalChapters())
                .coverUrl(book.getCoverUrl())
                .fileUrl(book.getId() == null ? null
                        : com.fryfrog.hub.common.util.MediaUrlSigner.sign(
                        "/api/v1/ebooks/" + book.getId() + "/file"))
                .progress(progress != null ? ProgressDTO.from(progress) : null)
                .build();
    }
}
