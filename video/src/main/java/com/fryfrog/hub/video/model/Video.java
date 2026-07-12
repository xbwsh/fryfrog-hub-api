package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.persistence.Index;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "videos", indexes = {
    @Index(name = "idx_video_title", columnList = "title"),
    @Index(name = "idx_video_tmdb_id", columnList = "tmdbId"),
    @Index(name = "idx_video_file_name", columnList = "fileName"),
    @Index(name = "idx_video_favorite", columnList = "favorite"),
    @Index(name = "idx_video_series_id", columnList = "series_id"),
    @Index(name = "idx_video_library_id", columnList = "library_id")
})
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

    @Schema(description = "时长（秒，精确）", example = "10380.0")
    private Double durationSeconds;

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

    @Schema(description = "编码等级", example = "Main 10")
    private String videoProfile;

    @Schema(description = "像素格式", example = "yuv420p10le")
    private String pixFmt;

    @Schema(description = "显示比例", example = "16:9")
    private String displayAspectRatio;

    @Schema(description = "音频编码", example = "AAC")
    private String audioCodec;

    @Schema(description = "声道布局", example = "stereo")
    private String audioChannelLayout;

    @Schema(description = "分辨率", example = "3840x2160")
    private String resolution;

    @Schema(description = "帧率（fps）", example = "24")
    private Double frameRate;

    @Schema(description = "比特率（kbps）", example = "15000")
    private Integer bitrateKbps;

    @Schema(description = "视频格式", example = "MKV")
    private String format;

    @Schema(description = "封面图片本地路径（竖屏海报）")
    private String coverArtPath;

    @Schema(description = "背景图片本地路径（横屏）")
    private String backdropLocalPath;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite = false;

    @Schema(description = "是否为成人内容", example = "false")
    private Boolean isAdult = false;

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

    @Schema(description = "Hanime ID", example = "123456")
    private String hanimeId;

    @Schema(description = "标签（逗号分隔）", example = "无码,步兵")
    @Column(columnDefinition = "TEXT")
    private String tags;

    @Schema(description = "观看次数", example = "12345")
    private Integer viewCount;

    @Schema(description = "制作商", example = "S1 NO.1 STYLE")
    private String studio;

    @Schema(description = "副标题", example = "超高清 4K")
    private String subtitle;

    @Schema(description = "元数据最后更新时间")
    private java.time.LocalDateTime metadataUpdatedAt;

    @Schema(description = "刮削尝试时间（用于跳过近期已尝试的视频）")
    private java.time.LocalDateTime scrapeAttemptedAt;

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

    @Schema(description = "播出状态", example = "Returning Series")
    private String status;

    @Schema(description = "所属资源库ID", example = "1")
    @Column(name = "library_id")
    private Long libraryId;

    @OneToMany(mappedBy = "video", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<VideoActor> actorEntities = new ArrayList<>();

    @OneToOne(mappedBy = "video", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private WatchProgress watchProgress;
}
