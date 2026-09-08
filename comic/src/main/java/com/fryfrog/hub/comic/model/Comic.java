package com.fryfrog.hub.comic.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comics", indexes = {
    @Index(name = "idx_comic_title", columnList = "title"),
    @Index(name = "idx_comic_author", columnList = "author"),
    @Index(name = "idx_comic_library", columnList = "library_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "漫画（系列/作品级）")
public class Comic extends BaseEntity {

    @Schema(description = "书名", example = "进击的巨人")
    @Column(nullable = false)
    private String title;

    @Schema(description = "作者", example = "谏山创")
    private String author;

    @Schema(description = "简介")
    @Column(columnDefinition = "TEXT")
    private String overview;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "丛书序号")
    private Integer seriesPart;

    @Schema(description = "外部刮削源 ID（Bangumi subject id），手动编辑时清除")
    private String sourceId;

    @Schema(description = "元数据来源: manual/scrape/scan", example = "scan")
    private String metadataSource;

    @Schema(description = "出版年份", example = "2009")
    private Integer pubYear;

    @Schema(description = "评分（0-5）", example = "4.5")
    private Double rating;

    @Schema(description = "作品目录或单文件路径（唯一标识）")
    @Column(unique = true, nullable = false)
    private String bookPath;

    @Schema(description = "封面本地路径")
    private String coverArtPath;

    @Schema(description = "卷/话数", example = "12")
    private Integer totalChapters;

    @Schema(description = "文件大小合计（字节）")
    private Long totalSize;

    @Schema(description = "所属资源库ID", example = "1")
    @Column(name = "library_id")
    private Long libraryId;

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        if (getId() == null || coverArtPath == null) return null;
        return com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/comics/" + getId() + "/cover");
    }
}
