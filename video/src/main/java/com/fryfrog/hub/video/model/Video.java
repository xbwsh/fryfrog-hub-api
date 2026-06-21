package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "videos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "视频信息")
public class Video extends BaseEntity {

    @Schema(description = "视频标题", example = "流浪地球2")
    @Column(nullable = false)
    private String title;

    @Schema(description = "导演", example = "郭帆")
    private String director;

    @Schema(description = "演员", example = "吴京,刘德华")
    private String actors;

    @Schema(description = "类型", example = "科幻")
    private String genre;

    @Schema(description = "发行年份", example = "2023")
    @Column(name = "\"year\"")
    private Integer year;

    @Schema(description = "时长（分钟）", example = "173")
    private Integer durationMinutes;

    @Schema(description = "文件完整路径")
    @Column(unique = true)
    private String filePath;

    @Schema(description = "文件名", example = "流浪地球2.mkv")
    @Column(nullable = false)
    private String fileName;

    @Schema(description = "文件大小（字节）", example = "10737418240")
    private Long fileSize;

    @Schema(description = "视频编码", example = "H.265")
    private String videoCodec;

    @Schema(description = "音频编码", example = "AAC")
    private String audioCodec;

    @Schema(description = "分辨率", example = "3840x2160")
    private String resolution;

    @Schema(description = "帧率（fps）", example = "24")
    private Double frameRate;

    @Schema(description = "比特率（kbps）", example = "15000")
    private Integer bitrateKbps;

    @Schema(description = "视频格式", example = "MKV")
    private String format;

    @Schema(description = "封面图片缓存路径")
    private String coverArtPath;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite = false;

    @Schema(description = "TMDB ID", example = "545611")
    private Long tmdbId;

    @Schema(description = "媒体类型（movie/tv）", example = "movie")
    private String mediaType;

    @Schema(description = "原始标题", example = "The Wandering Earth II")
    private String originalTitle;

    @Schema(description = "简介", example = "太阳即将毁灭...")
    @Column(columnDefinition = "TEXT")
    private String overview;

    @Schema(description = "海报图片URL")
    private String posterUrl;

    @Schema(description = "背景图片URL")
    private String backdropUrl;

    @Schema(description = "豆瓣/IMDB ID", example = "tt1454468")
    private String imdbId;

    @Schema(description = "评分", example = "8.3")
    private Double rating;

    @Schema(description = "评分人数", example = "12345")
    private Integer voteCount;

    @Schema(description = "简介来源", example = "tmdb")
    private String metadataSource;

    @Schema(description = "元数据最后更新时间")
    private java.time.LocalDateTime metadataUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id")
    @Schema(description = "所属系列")
    private VideoSeries series;

    @Schema(description = "季数", example = "1")
    private Integer seasonNumber;

    @Schema(description = "集数", example = "1")
    private Integer episodeNumber;

    @Schema(description = "是否为系列", example = "false")
    private Boolean isSeries = false;

    @Schema(description = "系列名称（来自 NFO 或目录名）")
    private String seriesName;
}
