package com.fryfrog.hub.audiobook.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "audiobooks", indexes = {
    @Index(name = "idx_audiobook_title", columnList = "title"),
    @Index(name = "idx_audiobook_author", columnList = "author"),
    @Index(name = "idx_audiobook_library", columnList = "library_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "有声书")
public class Audiobook extends BaseEntity {

    @Schema(description = "书名", example = "三体")
    @Column(nullable = false)
    private String title;

    @Schema(description = "作者", example = "刘慈欣")
    private String author;

    @Schema(description = "朗读者")
    private String narrator;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "丛书序号")
    private Integer seriesPart;

    @Schema(description = "所属资源库ID", example = "1")
    @Column(name = "library_id")
    private Long libraryId;

    @Schema(description = "书目目录绝对路径（唯一标识）")
    @Column(unique = true, nullable = false)
    private String bookPath;

    @Schema(description = "播放模式: SINGLE=单文件（如 M4B）, MULTI=多文件（每章一个文件）", example = "MULTI")
    @Column(nullable = false, length = 16)
    private String playType;

    @Schema(description = "单文件模式下的音频文件路径（MULTI 模式为空）")
    private String filePath;

    @Schema(description = "封面本地路径")
    private String coverArtPath;

    @Schema(description = "总时长（秒）", example = "36000.0")
    private Double totalDurationSeconds;

    @Schema(description = "曲目数", example = "24")
    private Integer trackCount;

    @Schema(description = "文件大小合计（字节）")
    private Long totalFileSize;

    public static final String TYPE_SINGLE = "SINGLE";
    public static final String TYPE_MULTI = "MULTI";

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        if (getId() == null || coverArtPath == null) return null;
        return com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/audiobooks/" + getId() + "/cover");
    }
}
