package com.fryfrog.hub.video.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbTvImages {

    @JsonProperty("logos")
    private List<Logo> logos;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Logo {
        @JsonProperty("file_path")
        private String filePath;

        @JsonProperty("width")
        private Integer width;

        @JsonProperty("height")
        private Integer height;

        @JsonProperty("iso_639_1")
        private String language;
    }
}
