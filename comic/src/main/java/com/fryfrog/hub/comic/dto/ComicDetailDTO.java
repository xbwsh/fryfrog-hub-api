package com.fryfrog.hub.comic.dto;

import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicChapter;
import com.fryfrog.hub.comic.model.ComicProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "漫画详情")
public class ComicDetailDTO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "丛书序号")
    private Integer seriesPart;

    @Schema(description = "元数据来源: manual/scrape/scan")
    private String metadataSource;

    @Schema(description = "出版年份")
    private Integer pubYear;

    @Schema(description = "评分（0-5）")
    private Double rating;

    @Schema(description = "卷/话数")
    private Integer totalChapters;

    @Schema(description = "封面 URL（签名）")
    private String coverUrl;

    @Schema(description = "卷/话列表")
    private List<ChapterDTO> chapters;

    @Schema(description = "当前用户阅读进度")
    private ProgressDTO progress;

    @Builder
    @Data
    @Schema(description = "卷/话")
    public static class ChapterDTO {

        @Schema(description = "ID")
        private Long id;

        @Schema(description = "卷/话索引")
        private Integer chapterIndex;

        @Schema(description = "卷/话名")
        private String title;

        @Schema(description = "页数")
        private Integer pageCount;

        @Schema(description = "类型: DIRECTORY/ARCHIVE")
        private String type;

        public static ChapterDTO from(ComicChapter chapter) {
            return ChapterDTO.builder()
                    .id(chapter.getId())
                    .chapterIndex(chapter.getChapterIndex())
                    .title(chapter.getTitle())
                    .pageCount(chapter.getPageCount())
                    .type(chapter.getType())
                    .build();
        }
    }

    @Builder
    @Data
    @Schema(description = "阅读进度")
    public static class ProgressDTO {

        @Schema(description = "当前卷/话索引")
        private Integer chapterIndex;

        @Schema(description = "当前页索引")
        private Integer pageIndex;

        @Schema(description = "是否读完")
        private Boolean completed;

        @Schema(description = "阅读进度百分比（0-100）")
        private Double progressPercent;

        public static ProgressDTO from(ComicProgress progress, Double progressPercent) {
            return ProgressDTO.builder()
                    .chapterIndex(progress.getChapterIndex())
                    .pageIndex(progress.getPageIndex())
                    .completed(progress.getCompleted())
                    .progressPercent(progressPercent)
                    .build();
        }
    }

    public static ComicDetailDTO from(Comic comic, List<ChapterDTO> chapters,
                                      ComicProgress progress, Double progressPercent) {
        return ComicDetailDTO.builder()
                .id(comic.getId())
                .title(comic.getTitle())
                .author(comic.getAuthor())
                .overview(comic.getOverview())
                .series(comic.getSeries())
                .seriesPart(comic.getSeriesPart())
                .metadataSource(comic.getMetadataSource())
                .pubYear(comic.getPubYear())
                .rating(comic.getRating())
                .totalChapters(comic.getTotalChapters())
                .coverUrl(comic.getCoverUrl())
                .chapters(chapters)
                .progress(progress != null ? ProgressDTO.from(progress, progressPercent) : null)
                .build();
    }
}
