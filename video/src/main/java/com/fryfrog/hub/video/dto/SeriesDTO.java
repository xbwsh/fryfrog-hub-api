package com.fryfrog.hub.video.dto;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@com.fasterxml.jackson.annotation.JsonPropertyOrder({"id", "type", "title", "coverUrl", "fanartUrl", "originalTitle", "overview"})
@Schema(description = "视频系列信息")
public class SeriesDTO {

    @Schema(description = "系列ID（独立视频为视频ID）")
    private Long id;

    @Schema(description = "条目类型: series=系列, standalone=独立视频")
    private String type;

    @Schema(description = "系列名称")
    private String title;

    @Schema(description = "封面URL")
    private String coverUrl;

    @Schema(description = "背景图URL")
    private String fanartUrl;

    @Schema(description = "剧集字标Logo URL")
    private String logoUrl;

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

    @Schema(description = "上映日期（精确到日）", example = "2022-04-09")
    private String releaseDate;

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

    @Schema(description = "是否为成人内容", example = "false")
    private Boolean isAdult;

    @Schema(description = "是否收藏")
    private Boolean favorite;

    @Schema(description = "实际集数")
    private Integer episodeCount;

    @Schema(description = "元数据目录路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String metadataDir;

    @Schema(description = "按季分组的剧集列表")
    private List<SeasonDTO> seasons;

    @Schema(description = "海报本地路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String posterLocalPath;

    @Schema(description = "背景图本地路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String backdropLocalPath;

    @Schema(description = "分辨率标签列表（去重，按清晰度降序）", example = "[\"4K\",\"1080p\"]")
    private List<String> resolutions;

    public static SeriesDTO fromEntity(VideoSeries series, List<VideoDTO> episodes, boolean favorite) {
        SeriesDTO dto = new SeriesDTO();
        dto.setId(series.getId());
        dto.setType("series");
        dto.setTitle(series.getTitle());
        dto.setCoverUrl(com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/video/series/" + series.getId() + "/cover"));
        dto.setFanartUrl(com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/video/series/" + series.getId() + "/fanart"));
        dto.setLogoUrl(series.getLogoApiUrl());
        dto.setOriginalTitle(series.getOriginalTitle());
        dto.setOverview(series.getOverview());
        dto.setMediaType(series.getMediaType());
        dto.setTmdbId(series.getTmdbId());
        dto.setRating(series.getRating());
        dto.setYear(series.getYear());
        dto.setReleaseDate(series.getReleaseDate());
        dto.setPosterUrl(series.getPosterUrl());
        dto.setBackdropUrl(series.getBackdropUrl());
        dto.setSeasonNumber(series.getSeasonNumber());
        dto.setNumberOfSeasons(series.getNumberOfSeasons());
        dto.setTotalEpisodes(series.getTotalEpisodes());
        dto.setEpisodeCount(series.getEpisodeCount());
        dto.setStatus(series.getStatus());
        dto.setIsAdult(series.getIsAdult());
        dto.setFavorite(favorite);
        dto.setMetadataDir(series.getMetadataDir());
        dto.setPosterLocalPath(series.getPosterLocalPath());
        dto.setBackdropLocalPath(series.getBackdropLocalPath());
        dto.setSeasons(groupEpisodesBySeason(series.getId(), episodes));
        dto.setResolutions(collectResolutions(episodes));
        return dto;
    }

    /**
     * 聚合剧集分辨率标签：去重 + 按清晰度降序（4K > 2K > 1080p > 720p > 480p > 其他）
     */
    private static List<String> collectResolutions(List<VideoDTO> episodes) {
        return episodes.stream()
                .map(VideoDTO::getResolutionLabel)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparingInt(SeriesDTO::resolutionRank))
                .toList();
    }

    private static int resolutionRank(String label) {
        return switch (label) {
            case "4K" -> 0;
            case "2K" -> 1;
            case "1080p" -> 2;
            case "720p" -> 3;
            case "480p" -> 4;
            default -> 5;
        };
    }

    private static List<SeasonDTO> groupEpisodesBySeason(Long seriesId, List<VideoDTO> episodes) {
        Map<Integer, List<VideoDTO>> grouped = new LinkedHashMap<>();
        for (VideoDTO ep : episodes) {
            int season = ep.getSeasonNumber() != null ? ep.getSeasonNumber() : 1;
            grouped.computeIfAbsent(season, k -> new ArrayList<>()).add(ep);
        }
        return grouped.entrySet().stream()
                .map(e -> SeasonDTO.of(seriesId, e.getKey(), e.getValue()))
                .toList();
    }

    public static SeriesDTO fromStandaloneVideo(Video video, VideoDTO episode, boolean favorite) {
        SeriesDTO dto = new SeriesDTO();
        dto.setId(video.getId());
        dto.setType("standalone");
        dto.setTitle(video.getTitle());
        dto.setCoverUrl(com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/video/" + video.getId() + "/cover"));
        dto.setFanartUrl(com.fryfrog.hub.common.util.MediaUrlSigner.sign("/api/v1/video/" + video.getId() + "/fanart"));
        dto.setLogoUrl(video.getLogoApiUrl());
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
        dto.setFavorite(favorite);
        dto.setSeasons(List.of(SeasonDTO.of(video.getId(), 1, List.of(episode))));
        String label = VideoDTO.resolutionLabel(video.getResolution());
        dto.setResolutions(label != null ? List.of(label) : List.of());
        return dto;
    }
}
