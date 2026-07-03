package com.fryfrog.hub.common.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "media_libraries", indexes = {
    @Index(name = "idx_library_type", columnList = "type"),
    @Index(name = "idx_library_enabled", columnList = "enabled")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "媒体资源库")
public class MediaLibrary extends BaseEntity {

    @Schema(description = "资源库名称", example = "电影库")
    @Column(nullable = false)
    private String name;

    @Schema(description = "目录路径", example = "/media/movies")
    @Column(nullable = false)
    private String path;

    @Schema(description = "媒体类型: MUSIC/COMIC/VIDEO/EBOOK", example = "VIDEO")
    @Column(nullable = false)
    private String type;

    @Schema(description = "视频子类型: MOVIE=电影, TV=电视剧, MIXED=混合（仅 type=VIDEO 时有效）", example = "MOVIE")
    private String subType;

    @Schema(description = "是否启用", example = "true")
    @Column(nullable = false)
    private Boolean enabled = true;

    @Schema(description = "排序", example = "0")
    private Integer sortOrder;

    @Schema(description = "备注说明")
    private String description;

    public enum Type {
        MUSIC, COMIC, VIDEO, EBOOK
    }

    public enum VideoSubType {
        MOVIE, TV, MIXED
    }

    public boolean isVideoType() {
        return Type.VIDEO.name().equalsIgnoreCase(type);
    }

    public boolean isMovieSubType() {
        return VideoSubType.MOVIE.name().equalsIgnoreCase(subType);
    }

    public boolean isTvSubType() {
        return VideoSubType.TV.name().equalsIgnoreCase(subType);
    }

    public boolean isMixedSubType() {
        return subType == null || VideoSubType.MIXED.name().equalsIgnoreCase(subType);
    }

    public String getMediaTypeFilter() {
        if (isMovieSubType()) return "movie";
        if (isTvSubType()) return "tv";
        return null; // MIXED or no subType: no filter
    }
}
