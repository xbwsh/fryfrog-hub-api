package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.persistence.Index;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "video_series", indexes = {
    @Index(name = "idx_series_tmdb_id", columnList = "tmdbId"),
    @Index(name = "idx_series_title", columnList = "title")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "视频系列（电视剧/多集电影）")
public class VideoSeries extends BaseEntity {

    @Schema(description = "系列名称", example = "爱心符号多一点")
    @Column(nullable = false)
    private String title;

    @Schema(description = "原始标题")
    private String originalTitle;

    @Schema(description = "简介")
    @Column(columnDefinition = "TEXT")
    private String overview;

    @Schema(description = "类型（tv/movie）", example = "tv")
    private String mediaType;

    @Schema(description = "TMDB ID", example = "318048")
    private Long tmdbId;

    @Schema(description = "IMDB ID")
    private String imdbId;

    @Schema(description = "评分")
    private Double rating;

    @Schema(description = "年份")
    private Integer year;

    @Schema(description = "海报URL（TMDB）")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String posterUrl;

    @Schema(description = "背景图URL（TMDB）")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String backdropUrl;

    @Schema(description = "海报本地路径")
    private String posterLocalPath;

    @Schema(description = "背景图本地路径")
    private String backdropLocalPath;

    @Schema(description = "元数据来源")
    private String metadataSource;

    @Schema(description = "播出状态", example = "Returning Series")
    private String status;

    @Schema(description = "是否为成人内容", example = "false")
    private Boolean isAdult = false;

    @Schema(description = "总季数")
    private Integer numberOfSeasons;

    @Schema(description = "季数", example = "1")
    private Integer seasonNumber = 1;

    @Schema(description = "总集数")
    private Integer totalEpisodes;

    @Schema(description = "元数据目录路径")
    private String metadataDir;

    @OneToMany(mappedBy = "series", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("episodeNumber ASC")
    @Schema(description = "包含的视频")
    private List<Video> videos = new ArrayList<>();

    public int getEpisodeCount() {
        return videos != null ? videos.size() : 0;
    }

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        if (getId() == null) return null;
        return "/api/v1/video/series/" + getId() + "/cover";
    }

    @com.fasterxml.jackson.annotation.JsonGetter("fanartUrl")
    public String getFanartUrl() {
        if (getId() == null) return null;
        return "/api/v1/video/series/" + getId() + "/fanart";
    }
}
