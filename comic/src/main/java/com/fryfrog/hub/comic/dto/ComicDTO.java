package com.fryfrog.hub.comic.dto;

import com.fryfrog.hub.comic.model.Comic;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "漫画信息（含API路径）")
public class ComicDTO {

    @Schema(description = "漫画ID")
    private Long id;

    @Schema(description = "封面URL")
    private String coverUrl;

    @Schema(description = "系列ID（关联 MediaSeries）")
    private Long seriesId;

    @Schema(description = "漫画标题", example = "进击的巨人")
    private String title;

    @Schema(description = "作者", example = "谏山创")
    private String author;

    @Schema(description = "系列名称", example = "进击的巨人")
    private String series;

    @Schema(description = "卷号", example = "1")
    private Integer volume;

    @Schema(description = "发行年份", example = "2009")
    private Integer year;

    @Schema(description = "类型/标签", example = "少年漫画")
    private String genre;

    @Schema(description = "摘要描述（单行本）")
    private String summary;

    @Schema(description = "系列简介（总览）")
    private String seriesSummary;

    @Schema(description = "文件名", example = "进击的巨人 Vol.1.cbz")
    private String fileName;

    @Schema(description = "文件大小（字节）", example = "52428800")
    private Long fileSize;

    @Schema(description = "总页数", example = "196")
    private Integer pageCount;

    @Schema(description = "漫画格式", example = "CBZ")
    private String format;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite;

    @Schema(description = "元数据来源（anilist）", example = "anilist")
    private String metadataSource;

    @Schema(description = "外部元数据 ID（AniList manga ID）", example = "104712")
    private Integer metadataSourceId;

    @Schema(description = "元数据最后更新时间")
    private LocalDateTime metadataUpdatedAt;

    @Schema(description = "原始标题", example = "進撃の巨人")
    private String originalTitle;

    @Schema(description = "评分", example = "8.3")
    private Double rating;

    @Schema(description = "出版社", example = "集英社")
    private String publisher;

    @Schema(description = "ISBN", example = "9784088820118")
    private String isbn;

    @Schema(description = "发售日期（单行本）", example = "2019-07-04")
    private String releaseDate;

    @Schema(description = "连载开始日期", example = "2019-03-25")
    private String serializationStart;

    @Schema(description = "是否有本地封面文件")
    private Boolean hasCover;

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        return id != null && Boolean.TRUE.equals(hasCover)
                ? "/api/v1/comic/" + id + "/cover" : null;
    }

    public static ComicDTO fromEntity(Comic comic, boolean hasCover) {
        ComicDTO dto = new ComicDTO();
        dto.setId(comic.getId());
        dto.setCoverUrl(hasCover ? "/api/v1/comic/" + comic.getId() + "/cover" : null);
        dto.setSeriesId(comic.getSeriesRef() != null ? comic.getSeriesRef().getId() : null);
        dto.setTitle(comic.getTitle());
        dto.setAuthor(comic.getAuthor());
        dto.setSeries(comic.getSeries());
        dto.setVolume(comic.getVolume());
        dto.setYear(comic.getYear());
        dto.setGenre(comic.getGenre());
        dto.setSummary(comic.getSummary());
        dto.setSeriesSummary(comic.getSeriesSummary());
        dto.setFileName(comic.getFileName());
        dto.setFileSize(comic.getFileSize());
        dto.setPageCount(comic.getPageCount());
        dto.setFormat(comic.getFormat());
        dto.setFavorite(comic.getFavorite());
        dto.setMetadataSource(comic.getMetadataSource());
        dto.setMetadataSourceId(comic.getMetadataSourceId());
        dto.setMetadataUpdatedAt(comic.getMetadataUpdatedAt());
        dto.setOriginalTitle(comic.getOriginalTitle());
        dto.setRating(comic.getRating());
        dto.setPublisher(comic.getPublisher());
        dto.setIsbn(comic.getIsbn());
        dto.setReleaseDate(comic.getReleaseDate());
        dto.setSerializationStart(comic.getSerializationStart());
        dto.setHasCover(hasCover);
        return dto;
    }
}
