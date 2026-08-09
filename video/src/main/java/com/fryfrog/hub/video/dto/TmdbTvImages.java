package com.fryfrog.hub.video.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * TMDB 电视剧图片资源（GET /tv/{id}/images），用于刮削剧集字标 logo。
 * 仅需 logos 数组，backdrops/posters 由其他接口覆盖。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbTvImages {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("logos")
    private List<Logo> logos;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Logo {
        @JsonProperty("file_path")
        private String filePath;

        @JsonProperty("file_type")
        private String fileType;

        @JsonProperty("iso_639_1")
        private String iso6391;

        @JsonProperty("width")
        private Integer width;

        @JsonProperty("height")
        private Integer height;

        @JsonProperty("vote_average")
        private Double voteAverage;

        @JsonProperty("vote_count")
        private Integer voteCount;
    }
}
