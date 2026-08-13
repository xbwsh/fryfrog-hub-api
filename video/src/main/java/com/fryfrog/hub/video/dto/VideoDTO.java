package com.fryfrog.hub.video.dto;

import com.fryfrog.hub.video.model.Video;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "视频信息（含元数据路径）")
public class VideoDTO {

    @Schema(description = "视频ID")
    private Long id;

    @Schema(description = "视频标题", example = "流浪地球2")
    private String title;

    @Schema(description = "封面URL")
    private String coverUrl;

    @Schema(description = "背景图URL")
    private String fanartUrl;

    @Schema(description = "字标Logo URL")
    private String logoUrl;

    @Schema(description = "流播放URL")
    private String streamUrl;

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

    @Schema(description = "上映日期（精确到日）", example = "2023-01-22")
    private String releaseDate;

    @Schema(description = "时长（分钟）", example = "173")
    private Integer durationMinutes;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "文件名", example = "流浪地球2.mkv")
    private String fileName;

    @Schema(description = "原始文件名（刮削/重命名前）", example = "[小组] 流浪地球2 1080p.mkv")
    private String originalFileName;

    @Schema(description = "文件大小（字节）", example = "10737418240")
    private Long fileSize;

    @Schema(description = "视频格式", example = "MKV")
    private String format;

    @Schema(description = "分辨率（宽x高）", example = "3840x2160")
    private String resolution;

    @Schema(description = "分辨率标签", example = "4K")
    private String resolutionLabel;

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

    @Schema(description = "竖屏海报本地路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String coverArtPath;

    @Schema(description = "横屏背景图本地路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String backdropLocalPath;

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

    @Schema(description = "是否为成人内容", example = "false")
    private Boolean isAdult;

public static VideoDTO fromEntity(Video video, boolean hasNfo, boolean hasPoster, boolean hasFanart,
                                     boolean hasMetadataDir, boolean favorite) {
        VideoDTO dto = new VideoDTO();
        dto.setId(video.getId());
        dto.setCoverUrl(com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/video/" + video.getId() + "/cover"));
        dto.setFanartUrl(com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/video/" + video.getId() + "/fanart"));
        dto.setLogoUrl(video.getLogoApiUrl());
        dto.setStreamUrl(com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/video/" + video.getId() + "/stream"));
        dto.setOriginalTitle(video.getOriginalTitle());
        dto.setDirector(video.getDirector());
        dto.setActors(video.getActors());
        dto.setGenre(video.getGenre());
        dto.setYear(video.getYear());
        dto.setReleaseDate(video.getReleaseDate());
        dto.setDurationMinutes(video.getDurationMinutes());
        dto.setOverview(video.getOverview());
        dto.setFileName(video.getFileName());
        dto.setOriginalFileName(video.getOriginalFileName());
        dto.setFileSize(video.getFileSize());
        dto.setFormat(video.getFormat());
        dto.setResolution(video.getResolution());
        dto.setResolutionLabel(resolutionLabel(video.getResolution()));
        dto.setFavorite(favorite);
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
        dto.setCoverArtPath(video.getCoverArtPath());
        dto.setBackdropLocalPath(video.getBackdropLocalPath());
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

    /**
     * 将 "宽x高" 分辨率转为展示标签：4K / 1080p / 720p / 480p 等
     */
    public static String resolutionLabel(String resolution) {
        if (resolution == null || resolution.isBlank()) return null;
        try {
            String[] parts = resolution.toLowerCase().split("x");
            if (parts.length != 2) return null;
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            int longerSide = Math.max(width, height);
            if (longerSide >= 3800) return "4K";
            if (longerSide >= 2500) return "2K";
            if (longerSide >= 1800) return "1080p";
            if (longerSide >= 1200) return "720p";
            if (longerSide >= 700) return "480p";
            return height + "p";
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
