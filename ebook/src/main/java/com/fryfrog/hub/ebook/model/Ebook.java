package com.fryfrog.hub.ebook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "ebooks", indexes = {
    @Index(name = "idx_ebook_title", columnList = "title"),
    @Index(name = "idx_ebook_author", columnList = "author"),
    @Index(name = "idx_ebook_library", columnList = "library_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "电子书")
public class Ebook extends BaseEntity {

    public static final String FORMAT_EPUB = "EPUB";
    public static final String FORMAT_PDF = "PDF";
    public static final String FORMAT_MOBI = "MOBI";

    @Schema(description = "书名", example = "三体")
    @Column(nullable = false)
    private String title;

    @Schema(description = "作者", example = "刘慈欣")
    private String author;

    @Schema(description = "出版社")
    private String publisher;

    @Schema(description = "语言", example = "zh")
    private String language;

    @Schema(description = "出版年份", example = "2008")
    private Integer pubYear;

    @Schema(description = "简介")
    @Column(columnDefinition = "TEXT")
    private String overview;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "丛书序号")
    private Integer seriesPart;

    @Schema(description = "外部刮削源 ID（Bangumi subject id 等），手动编辑时清除")
    private String sourceId;

    @Schema(description = "元数据来源: manual/scrape/scan", example = "scan")
    private String metadataSource;

    @Schema(description = "格式: EPUB/PDF/MOBI", example = "EPUB")
    @Column(nullable = false, length = 16)
    private String format;

    @Schema(description = "文件绝对路径（唯一标识）")
    @Column(unique = true, nullable = false)
    private String filePath;

    @Schema(description = "文件修改时间（epoch 毫秒，增量扫描用）")
    private Long fileMtime;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "封面本地路径")
    private String coverArtPath;

    @Schema(description = "章节数（EPUB spine 数）", example = "24")
    private Integer totalChapters;

    @Schema(description = "所属资源库ID", example = "1")
    @Column(name = "library_id")
    private Long libraryId;

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        if (getId() == null || coverArtPath == null) return null;
        return com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/ebooks/" + getId() + "/cover");
    }
}
