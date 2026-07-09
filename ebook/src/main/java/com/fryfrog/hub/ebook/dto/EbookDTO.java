package com.fryfrog.hub.ebook.dto;

import com.fryfrog.hub.ebook.model.Ebook;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "电子书信息（含API路径）")
public class EbookDTO {

    @Schema(description = "电子书ID")
    private Long id;

    @Schema(description = "系列ID（关联 MediaSeries）")
    private Long seriesId;

    @Schema(description = "书名", example = "三体")
    private String title;

    @Schema(description = "作者", example = "刘慈欣")
    private String author;

    @Schema(description = "系列名称", example = "三体")
    private String series;

    @Schema(description = "卷号", example = "1")
    private Integer volume;

    @Schema(description = "出版年份", example = "2008")
    private Integer year;

    @Schema(description = "类型", example = "科幻")
    private String genre;

    @Schema(description = "简介")
    private String summary;

    @Schema(description = "系列简介（总览）")
    private String seriesSummary;

    @Schema(description = "文件名", example = "三体.epub")
    private String fileName;

    @Schema(description = "文件大小（字节）", example = "1048576")
    private Long fileSize;

    @Schema(description = "总页数/章节数", example = "350")
    private Integer pageCount;

    @Schema(description = "电子书格式", example = "EPUB")
    private String format;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite;

    @Schema(description = "元数据来源（bangumi/openLibrary）", example = "bangumi")
    private String metadataSource;

    @Schema(description = "外部元数据 ID（Bangumi ID 或 Open Library ID）")
    private String metadataSourceId;

    @Schema(description = "元数据最后更新时间")
    private LocalDateTime metadataUpdatedAt;

    @Schema(description = "原始标题")
    private String originalTitle;

    @Schema(description = "评分", example = "8.5")
    private Double rating;

    @Schema(description = "出版社", example = "重庆出版社")
    private String publisher;

    @Schema(description = "ISBN", example = "9787536692930")
    private String isbn;

    @Schema(description = "出版日期", example = "2008-01-01")
    private String releaseDate;

    @Schema(description = "语言", example = "zh-CN")
    private String language;

    @Schema(description = "是否有本地封面文件")
    private Boolean hasCover;

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        return id != null && Boolean.TRUE.equals(hasCover)
                ? "/api/v1/ebook/" + id + "/cover" : null;
    }

    @com.fasterxml.jackson.annotation.JsonGetter("downloadUrl")
    public String getDownloadUrl() {
        return "/api/v1/ebook/" + id + "/download";
    }

    @com.fasterxml.jackson.annotation.JsonGetter("readUrl")
    public String getReadUrl() {
        return "/api/v1/ebook/" + id + "/read";
    }

    public static EbookDTO fromEntity(Ebook ebook, boolean hasCover) {
        EbookDTO dto = new EbookDTO();
        dto.setId(ebook.getId());
        dto.setSeriesId(ebook.getSeriesRef() != null ? ebook.getSeriesRef().getId() : null);
        dto.setTitle(ebook.getTitle());
        dto.setAuthor(ebook.getAuthor());
        dto.setSeries(ebook.getSeries());
        dto.setVolume(ebook.getVolume());
        dto.setYear(ebook.getYear());
        dto.setGenre(ebook.getGenre());
        dto.setSummary(ebook.getDescription());
        dto.setFileName(ebook.getFileName());
        dto.setFileSize(ebook.getFileSize());
        dto.setPageCount(ebook.getPageCount());
        dto.setFormat(ebook.getFormat());
        dto.setFavorite(ebook.getFavorite());
        dto.setLanguage(ebook.getLanguage());
        dto.setPublisher(ebook.getPublisher());
        dto.setIsbn(ebook.getIsbn());
        dto.setHasCover(hasCover);

        if (ebook.getBangumiId() != null) {
            dto.setMetadataSource("bangumi");
            dto.setMetadataSourceId(String.valueOf(ebook.getBangumiId()));
        } else if (ebook.getOpenLibraryId() != null) {
            dto.setMetadataSource("openLibrary");
            dto.setMetadataSourceId(ebook.getOpenLibraryId());
        }

        return dto;
    }
}
