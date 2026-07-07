package com.fryfrog.hub.ebook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "ebooks", indexes = {
    @Index(name = "idx_ebook_title", columnList = "title"),
    @Index(name = "idx_ebook_author", columnList = "author"),
    @Index(name = "idx_ebook_favorite", columnList = "favorite")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "电子书信息")
public class Ebook extends BaseEntity {

    @Schema(description = "书名", example = "三体")
    @Column(nullable = false)
    private String title;

    @Schema(description = "作者", example = "刘慈欣")
    private String author;

    @Schema(description = "系列名称", example = "三体")
    private String series;

    @Schema(description = "卷号", example = "1")
    private Integer volume;

    @Schema(description = "出版社", example = "重庆出版社")
    private String publisher;

    @Schema(description = "ISBN", example = "9787536692930")
    private String isbn;

    @Schema(description = "出版年份", example = "2008")
    @Column(name = "\"year\"")
    private Integer year;

    @Schema(description = "类型", example = "科幻")
    private String genre;

    @Schema(description = "简介")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "文件完整路径")
    @Column(unique = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String filePath;

    @Schema(description = "文件名", example = "三体.epub")
    @Column(nullable = false)
    private String fileName;

    @Schema(description = "文件大小（字节）", example = "1048576")
    private Long fileSize;

    @Schema(description = "电子书格式", example = "EPUB")
    private String format;

    @Schema(description = "总页数/章节数", example = "350")
    private Integer pageCount;

    @Schema(description = "语言", example = "zh-CN")
    private String language;

    @Schema(description = "封面图片缓存路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String coverArtPath;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite = false;

    @Schema(description = "Bangumi条目ID")
    private Integer bangumiId;

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        return "/api/v1/ebook/" + getId() + "/cover";
    }

    @com.fasterxml.jackson.annotation.JsonGetter("downloadUrl")
    public String getDownloadUrl() {
        return "/api/v1/ebook/" + getId() + "/download";
    }

    @com.fasterxml.jackson.annotation.JsonGetter("readUrl")
    public String getReadUrl() {
        return "/api/v1/ebook/" + getId() + "/read";
    }
}
