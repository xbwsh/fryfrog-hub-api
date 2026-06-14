package com.fryfrog.hub.comic.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "comics")
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

    @Schema(description = "系列名称", example = "进击的巨人")
    private String series;

    @Schema(description = "卷号", example = "1")
    private Integer volume;

    @Schema(description = "发行年份", example = "2009")
    @Column(name = "\"year\"")
    private Integer year;

    @Schema(description = "类型/标签", example = "少年漫画")
    private String genre;

    @Schema(description = "摘要描述")
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Schema(description = "文件完整路径")
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
    private String coverArtPath;

    @Schema(description = "缩略图缓存目录")
    private String thumbnailDirPath;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite = false;
}