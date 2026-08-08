package com.fryfrog.hub.video.dto;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "视频系列列表信息（轻量版，用于列表页）")
public class SeriesListDTO {

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

    @Schema(description = "原始标题")
    private String originalTitle;

    @Schema(description = "类型（tv/movie）")
    private String mediaType;

    @Schema(description = "评分")
    private Double rating;

    @Schema(description = "年份")
    private Integer year;

    @Schema(description = "总季数")
    private Integer numberOfSeasons;

    @Schema(description = "总集数")
    private Integer totalEpisodes;

    @Schema(description = "实际集数")
    private Integer episodeCount;

    @Schema(description = "是否为成人内容")
    private Boolean isAdult;

    @Schema(description = "是否包含成人内容的集（用于隐私模式过滤）")
    private Boolean hasAdultEpisodes;

    public static SeriesListDTO fromEntity(VideoSeries series, List<Video> episodes) {
        SeriesListDTO dto = new SeriesListDTO();
        dto.setId(series.getId());
        dto.setType("series");
        dto.setTitle(series.getTitle());
        dto.setCoverUrl("/api/v1/video/series/" + series.getId() + "/cover");
        dto.setFanartUrl("/api/v1/video/series/" + series.getId() + "/fanart");
        dto.setOriginalTitle(series.getOriginalTitle());
        dto.setMediaType(series.getMediaType());
        dto.setRating(series.getRating());
        dto.setYear(series.getYear());
        dto.setNumberOfSeasons(series.getNumberOfSeasons());
        dto.setTotalEpisodes(series.getTotalEpisodes());
        dto.setEpisodeCount(series.getEpisodeCount());
        dto.setIsAdult(series.getIsAdult());
        dto.setHasAdultEpisodes(episodes.stream().anyMatch(v -> Boolean.TRUE.equals(v.getIsAdult())));
        return dto;
    }

    public static SeriesListDTO fromStandaloneVideo(Video video) {
        SeriesListDTO dto = new SeriesListDTO();
        dto.setId(video.getId());
        dto.setType("standalone");
        dto.setTitle(video.getTitle());
        dto.setCoverUrl("/api/v1/video/" + video.getId() + "/cover");
        dto.setFanartUrl("/api/v1/video/" + video.getId() + "/fanart");
        dto.setOriginalTitle(video.getOriginalTitle());
        dto.setMediaType(video.getMediaType());
        dto.setRating(video.getRating());
        dto.setYear(video.getYear());
        dto.setNumberOfSeasons(null);
        dto.setTotalEpisodes(1);
        dto.setEpisodeCount(1);
        dto.setIsAdult(video.getIsAdult());
        dto.setHasAdultEpisodes(Boolean.TRUE.equals(video.getIsAdult()));
        return dto;
    }
}
