package com.fryfrog.hub.video.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbTvDetail {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("original_name")
    private String originalName;

    @JsonProperty("overview")
    private String overview;

    @JsonProperty("first_air_date")
    private String firstAirDate;

    @JsonProperty("number_of_seasons")
    private Integer numberOfSeasons;

    @JsonProperty("number_of_episodes")
    private Integer numberOfEpisodes;

    @JsonProperty("genres")
    private List<TmdbMovieDetail.Genre> genres;

    @JsonProperty("created_by")
    private List<Creator> createdBy;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("backdrop_path")
    private String backdropPath;

    @JsonProperty("vote_average")
    private Double voteAverage;

    @JsonProperty("vote_count")
    private Integer voteCount;

    @JsonProperty("status")
    private String status;

    @JsonProperty("tagline")
    private String tagline;

    public Integer getYear() {
        if (firstAirDate != null && firstAirDate.length() >= 4) {
            try {
                return Integer.parseInt(firstAirDate.substring(0, 4));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public String getDirector() {
        if (createdBy != null && !createdBy.isEmpty()) {
            return createdBy.stream()
                    .map(Creator::getName)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        return null;
    }

    public String getGenres() {
        if (genres != null) {
            return genres.stream()
                    .map(TmdbMovieDetail.Genre::getName)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Creator {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("name")
        private String name;
    }
}
