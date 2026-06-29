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

    @Schema(description = "资源库类型: MOVIE=电影, TV=电视剧, MIXED=混合", example = "MOVIE")
    @Column(nullable = false)
    private String type;

    @Schema(description = "是否启用", example = "true")
    @Column(nullable = false)
    private Boolean enabled = true;

    @Schema(description = "排序", example = "0")
    private Integer sortOrder = 0;

    @Schema(description = "备注说明")
    private String description;

    public enum Type {
        MOVIE, TV, MIXED
    }

    public boolean isMovieType() {
        return "MOVIE".equalsIgnoreCase(type);
    }

    public boolean isTvType() {
        return "TV".equalsIgnoreCase(type);
    }

    public boolean isMixedType() {
        return "MIXED".equalsIgnoreCase(type);
    }

    public String getMediaTypeFilter() {
        if (isMovieType()) return "movie";
        if (isTvType()) return "tv";
        return null; // MIXED: no filter
    }
}
