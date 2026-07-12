package com.fryfrog.hub.video.dto;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "视频系列信息")
public class SeriesDTO {

    @Schema(description = "系列ID（独立视频为视频ID）")
    private Long id;

    @Schema(description = "条目类型: series=系列, standalone=独立视频")
    private String type;

    @Schema(description = "系列名称")
    private String title;

    @Schema(description = "原始标题")
    private String originalTitle;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "类型（tv/movie）")
    private String mediaType;

    @Schema(description = "TMDB ID")
    private Long tmdbId;

    @Schema(description = "评分")
    private Double rating;

    @Schema(description = "年份")
    private Integer year;

    @Schema(description = "海报URL")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String posterUrl;

    @Schema(description = "背景图URL")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String backdropUrl;

    @Schema(description = "季数")
    private Integer seasonNumber;

    @Schema(description = "总季数")
    private Integer numberOfSeasons;

    @Schema(description = "总集数")
    private Integer totalEpisodes;

    @Schema(description = "播出状态", example = "Returning Series")
    private String status;

    @Schema(description = "实际集数")
    private Integer episodeCount;

    @Schema(description = "元数据目录路径")
    private String metadataDir;

    @Schema(description = "包含的视频列表")
    private List<VideoDTO> episodes;

    @Schema(description = "海报本地路径")
    private String posterLocalPath;

    @Schema(description = "背景图本地路径")
    private String backdropLocalPath;

    public static SeriesDTO fromEntity(VideoSeries series, List<VideoDTO> episodes) {
        SeriesDTO dto = new SeriesDTO();
        dto.setId(series.getId());
        dto.setType("series");
        dto.setTitle(series.getTitle());
        dto.setOriginalTitle(series.getOriginalTitle());
        dto.setOverview(series.getOverview());
        dto.setMediaType(series.getMediaType());
        dto.setTmdbId(series.getTmdbId());
        dto.setRating(series.getRating());
        dto.setYear(series.getYear());
        dto.setPosterUrl(series.getPosterUrl());
        dto.setBackdropUrl(series.getBackdropUrl());
        dto.setSeasonNumber(series.getSeasonNumber());
        dto.setNumberOfSeasons(series.getNumberOfSeasons());
        dto.setTotalEpisodes(series.getTotalEpisodes());
        dto.setEpisodeCount(series.getEpisodeCount());
        dto.setStatus(series.getStatus());
        dto.setMetadataDir(series.getMetadataDir());
        dto.setPosterLocalPath(series.getPosterLocalPath());
        dto.setBackdropLocalPath(series.getBackdropLocalPath());
        dto.setEpisodes(episodes);
        return dto;
    }

    public static SeriesDTO fromStandaloneVideo(Video video, VideoDTO episode) {
        SeriesDTO dto = new SeriesDTO();
        dto.setId(video.getId());
        dto.setType("standalone");
        dto.setTitle(video.getTitle());
        dto.setOriginalTitle(video.getOriginalTitle());
        dto.setOverview(video.getOverview());
        dto.setMediaType(video.getMediaType());
        dto.setTmdbId(video.getTmdbId());
        dto.setRating(video.getRating());
        dto.setYear(video.getYear());
        dto.setPosterUrl(video.getPosterUrl());
        dto.setBackdropUrl(video.getBackdropUrl());
        dto.setSeasonNumber(null);
        dto.setNumberOfSeasons(null);
        dto.setTotalEpisodes(1);
        dto.setEpisodeCount(1);
        dto.setStatus(video.getStatus());
        dto.setPosterLocalPath(video.getCoverArtPath());
        dto.setBackdropLocalPath(video.getBackdropLocalPath());
        dto.setEpisodes(List.of(episode));
        return dto;
    }

    @com.fasterxml.jackson.annotation.JsonGetter("coverUrl")
    public String getCoverUrl() {
        if (id == null) return null;
        if ("standalone".equals(type)) {
            return "/api/v1/video/" + id + "/cover";
        }
        return "/api/v1/video/series/" + id + "/cover";
    }

    @com.fasterxml.jackson.annotation.JsonGetter("fanartUrl")
    public String getFanartUrl() {
        if (id == null) return null;
        if ("standalone".equals(type)) {
            return "/api/v1/video/" + id + "/fanart";
        }
        return "/api/v1/video/series/" + id + "/fanart";
    }
}
