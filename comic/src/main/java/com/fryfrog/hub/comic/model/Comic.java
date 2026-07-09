package com.fryfrog.hub.comic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fryfrog.hub.common.model.BaseEntity;
import com.fryfrog.hub.common.model.MediaSeries;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "comics", indexes = {
    @Index(name = "idx_comic_title", columnList = "title"),
    @Index(name = "idx_comic_favorite", columnList = "favorite"),
    @Index(name = "idx_comic_file_path", columnList = "filePath"),
    @Index(name = "idx_comic_series_id", columnList = "series_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "漫画信息")
public class Comic extends BaseEntity {

    @Schema(description = "漫画标题", example = "进击的巨人")
    @Column(nullable = false)
    private String title;

    @Schema(description = "作者", example = "谏山创")
    private String author;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "series_id")
    @JsonIgnore
    private MediaSeries seriesRef;

    @Schema(description = "系列名称（过渡字段，绑定后由 seriesRef 提供）")
    @Column(name = "series")
    private String seriesName;

    @Schema(description = "卷号", example = "1")
    private Integer volume;

    @Schema(description = "发行年份", example = "2009")
    @Column(name = "\"year\"")
    private Integer year;

    @Schema(description = "类型/标签", example = "少年漫画")
    private String genre;

    @Schema(description = "摘要描述（单行本）")
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Schema(description = "系列简介（总览）")
    @Column(columnDefinition = "TEXT")
    private String seriesSummary;

    @Schema(description = "文件完整路径")
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Column(unique = true)
    private String filePath;

    @Schema(description = "文件名", example = "进击的巨人 Vol.1.cbz")
    @Column(nullable = false)
    private String fileName;

    @Schema(description = "文件大小（字节）", example = "52428800")
    private Long fileSize;

    @Schema(description = "总页数", example = "196")
    private Integer pageCount;

    @Schema(description = "漫画格式", example = "CBZ")
    private String format;

    @Schema(description = "封面图片缓存路径")
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    private String coverArtPath;

    @Schema(description = "缩略图缓存目录")
    @JsonIgnore
    private String thumbnailDirPath;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite = false;

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

    @Schema(description = "封面图片URL")
    @JsonIgnore
    private String posterUrl;

    @Schema(description = "出版社", example = "集英社")
    private String publisher;

    @Schema(description = "ISBN", example = "9784088820118")
    private String isbn;

    @Schema(description = "发售日期（单行本）", example = "2019-07-04")
    private String releaseDate;

    @Schema(description = "连载开始日期", example = "2019-03-25")
    private String serializationStart;

    @JsonIgnore
    public String getCoverArtPath() {
        return coverArtPath;
    }

    @JsonIgnore
    public String getFilePath() {
        return filePath;
    }

    @Schema(description = "系列名称")
    @com.fasterxml.jackson.annotation.JsonGetter("series")
    public String getSeries() {
        return seriesRef != null ? seriesRef.getTitle() : seriesName;
    }
}
