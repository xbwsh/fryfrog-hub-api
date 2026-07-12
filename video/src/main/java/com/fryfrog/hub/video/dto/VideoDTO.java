package com.fryfrog.hub.video.dto;

import com.fryfrog.hub.video.model.Video;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "视频信息（含元数据路径）")
public class VideoDTO {

    private static final java.util.Set<String> INCOMPATIBLE_AUDIO = java.util.Set.of(
            "eac3", "dts", "truehd", "dca", "mlp"
    );

    @Schema(description = "视频ID")
    private Long id;

    @Schema(description = "视频标题", example = "流浪地球2")
    private String title;

    @Schema(description = "原始标题", example = "The Wandering Earth II")
    private String originalTitle;

    @Schema(description = "导演", example = "郭帆")
    private String director;

    @Schema(description = "演员", example = "吴京,刘德华")
    private String actors;

    @Schema(description = "类型", example = "科幻")
    private String genre;

    @Schema(description = "发行年份", example = "2023")
    private Integer year;

    @Schema(description = "时长（分钟）", example = "173")
    private Integer durationMinutes;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "文件名", example = "流浪地球2.mkv")
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

    @Schema(description = "是否收藏")
    private Boolean favorite;

    @Schema(description = "TMDB ID", example = "545611")
    private Long tmdbId;

    @Schema(description = "媒体类型（movie/tv）", example = "movie")
    private String mediaType;

    @Schema(description = "海报图片URL")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String posterUrl;

    @Schema(description = "背景图片URL")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String backdropUrl;

    @Schema(description = "IMDB ID", example = "tt1454468")
    private String imdbId;

    @Schema(description = "评分", example = "8.3")
    private Double rating;

    @Schema(description = "评分人数", example = "12345")
    private Integer voteCount;

    @Schema(description = "播出状态", example = "Returning Series")
    private String status;

    @Schema(description = "元数据来源", example = "tmdb")
    private String metadataSource;

    @Schema(description = "元数据最后更新时间")
    private LocalDateTime metadataUpdatedAt;

    @Schema(description = "是否有元数据目录")
    private Boolean hasMetadataDir;

    @Schema(description = "是否有NFO文件")
    private Boolean hasNfo;

    @Schema(description = "是否有竖屏海报")
    private Boolean hasPoster;

    @Schema(description = "是否有横屏背景图")
    private Boolean hasFanart;

    @Schema(description = "是否已刮削元数据")
    private Boolean scraped;

    @Schema(description = "是否为系列剧集")
    private Boolean isSeries;

    @Schema(description = "所属资源库ID")
    private Long libraryId;

    @Schema(description = "所属系列ID")
    private Long seriesId;

    @Schema(description = "所属系列名称")
    private String seriesTitle;

    @Schema(description = "季数")
    private Integer seasonNumber;

    @Schema(description = "集数")
    private Integer episodeNumber;

    @Schema(description = "观看进度（秒）")
    private Double watchPosition;

    @Schema(description = "观看进度百分比", example = "50.0")
    private Double watchProgressPercent;

    @Schema(description = "是否已看完")
    private Boolean watched;

    @Schema(description = "音频是否浏览器不兼容（需用本地播放器）", example = "true")
    private Boolean audioIncompatible;

    @Schema(description = "是否为成人内容", example = "false")
    private Boolean isAdult;

    public static VideoDTO fromEntity(Video video, boolean hasNfo, boolean hasPoster, boolean hasFanart, boolean hasMetadataDir) {
        VideoDTO dto = new VideoDTO();
        dto.setId(video.getId());
        dto.setTitle(video.getTitle());
        dto.setOriginalTitle(video.getOriginalTitle());
        dto.setDirector(video.getDirector());
        dto.setActors(video.getActors());
        dto.setGenre(video.getGenre());
        dto.setYear(video.getYear());
        dto.setDurationMinutes(video.getDurationMinutes());
        dto.setOverview(video.getOverview());
        dto.setFileName(video.getFileName());
        dto.setFileSize(video.getFileSize());
        dto.setVideoCodec(video.getVideoCodec());
        dto.setVideoProfile(video.getVideoProfile());
        dto.setPixFmt(video.getPixFmt());
        dto.setDisplayAspectRatio(video.getDisplayAspectRatio());
        dto.setAudioCodec(video.getAudioCodec());
        dto.setAudioChannelLayout(video.getAudioChannelLayout());
        dto.setAudioIncompatible(video.getAudioCodec() != null
                && INCOMPATIBLE_AUDIO.stream().anyMatch(video.getAudioCodec().toLowerCase()::contains));
        dto.setResolution(video.getResolution());
        dto.setFrameRate(video.getFrameRate());
        dto.setBitrateKbps(video.getBitrateKbps());
        dto.setFormat(video.getFormat());
        dto.setFavorite(video.getFavorite());
        dto.setTmdbId(video.getTmdbId());
        dto.setMediaType(video.getMediaType());
        dto.setPosterUrl(video.getPosterUrl());
        dto.setBackdropUrl(video.getBackdropUrl());
        dto.setImdbId(video.getImdbId());
        dto.setRating(video.getRating());
        dto.setVoteCount(video.getVoteCount());
        dto.setStatus(video.getStatus());
        dto.setMetadataSource(video.getMetadataSource());
        dto.setMetadataUpdatedAt(video.getMetadataUpdatedAt());
        dto.setHasNfo(hasNfo);
        dto.setHasPoster(hasPoster);
        dto.setHasFanart(hasFanart);
        dto.setHasMetadataDir(hasMetadataDir);
        dto.setScraped(video.getTmdbId() != null);
        dto.setIsSeries(video.getIsSeries());
        dto.setIsAdult(video.getIsAdult());
        dto.setLibraryId(video.getLibraryId());
        dto.setSeasonNumber(video.getSeasonNumber());
        dto.setEpisodeNumber(video.getEpisodeNumber());
        if (video.getSeries() != null) {
            dto.setSeriesId(video.getSeries().getId());
            dto.setSeriesTitle(video.getSeries().getTitle());
        }
        return dto;
    }

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        return id != null ? "/api/v1/video/" + id + "/cover" : null;
    }

    @com.fasterxml.jackson.annotation.JsonGetter("fanartUrl")
    public String getFanartUrl() {
        return id != null ? "/api/v1/video/" + id + "/fanart" : null;
    }

    @com.fasterxml.jackson.annotation.JsonGetter("streamUrl")
    public String getStreamUrl() {
        return id != null ? "/api/v1/video/" + id + "/stream" : null;
    }
}
