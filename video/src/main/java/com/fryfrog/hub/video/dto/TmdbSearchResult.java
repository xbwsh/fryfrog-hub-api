package com.fryfrog.hub.video.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbSearchResult {

    @JsonProperty("page")
    private Integer page;

    @JsonProperty("results")
    private List<TmdbSearchItem> results;

    @JsonProperty("total_pages")
    private Integer totalPages;

    @JsonProperty("total_results")
    private Integer totalResults;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TmdbSearchItem {

        @JsonProperty("id")
        private Long id;

        @JsonProperty("title")
        private String title;

        @JsonProperty("original_title")
        private String originalTitle;

        @JsonProperty("name")
        private String name;

        @JsonProperty("original_name")
        private String originalName;

        @JsonProperty("overview")
        private String overview;

        @JsonProperty("release_date")
        private String releaseDate;

        @JsonProperty("first_air_date")
        private String firstAirDate;

        @JsonProperty("poster_path")
        private String posterPath;

        @JsonProperty("backdrop_path")
        private String backdropPath;

        @JsonProperty("genre_ids")
        private List<Integer> genreIds;

        @JsonProperty("vote_average")
        private Double voteAverage;

        @JsonProperty("vote_count")
        private Integer voteCount;

        @JsonProperty("media_type")
        private String mediaType;

        @JsonProperty("popularity")
        private Double popularity;

        @JsonProperty("adult")
        private Boolean adult;

        public String getTitle() {
            return title != null ? title : name;
        }

        public String getOriginalTitle() {
            return originalTitle != null ? originalTitle : originalName;
        }

        public String getReleaseDate() {
            return releaseDate != null ? releaseDate : firstAirDate;
        }

        public Integer getYear() {
            String date = getReleaseDate();
            if (date != null && date.length() >= 4) {
                try {
                    return Integer.parseInt(date.substring(0, 4));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
    }
}
