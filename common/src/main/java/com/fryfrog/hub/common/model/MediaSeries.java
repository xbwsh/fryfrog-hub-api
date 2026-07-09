package com.fryfrog.hub.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonGetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "media_series", indexes = {
    @Index(name = "idx_media_series_title", columnList = "title"),
    @Index(name = "idx_media_series_media_type", columnList = "mediaType")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "媒体系列（漫画/电子书共享）")
public class MediaSeries extends BaseEntity {

    @Schema(description = "系列名称")
    @Column(nullable = false)
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "类型/标签")
    private String genre;

    @Schema(description = "原始标题")
    private String originalTitle;

    @Schema(description = "媒体类型", example = "comic")
    @Column(length = 16)
    private String mediaType;

    @Schema(description = "元数据来源", example = "bangumi")
    private String metadataSource;

    @Schema(description = "外部元数据 ID")
    private Integer metadataSourceId;

    @Schema(description = "评分")
    private Double rating;

    @Schema(description = "年份")
    private Integer year;

    @Schema(description = "系列简介")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "系列封面本地路径")
    @JsonIgnore
    private String coverArtPath;

    @Schema(description = "连载开始日期")
    private String serializationStart;

    @Schema(description = "是否收藏")
    private Boolean favorite = false;

    @OneToMany(mappedBy = "series", cascade = CascadeType.REMOVE, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<MediaSeriesCharacter> characters = new ArrayList<>();

    @JsonGetter("coverUrl")
    public String getCoverUrl() {
        if (getId() == null) return null;
        return "/api/v1/media/series/" + getId() + "/cover";
    }
}
