package com.fryfrog.hub.video.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbMovieDetail {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("original_title")
    private String originalTitle;

    @JsonProperty("overview")
    private String overview;

    @JsonProperty("release_date")
    private String releaseDate;

    @JsonProperty("runtime")
    private Integer runtime;

    @JsonProperty("genres")
    private List<Genre> genres;

    @JsonProperty("credits")
    private Credits credits;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("backdrop_path")
    private String backdropPath;

    @JsonProperty("vote_average")
    private Double voteAverage;

    @JsonProperty("vote_count")
    private Integer voteCount;

    @JsonProperty("imdb_id")
    private String imdbId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("tagline")
    private String tagline;

    public Integer getYear() {
        if (releaseDate != null && releaseDate.length() >= 4) {
            try {
                return Integer.parseInt(releaseDate.substring(0, 4));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public String getDirector() {
        if (credits != null && credits.getCrew() != null) {
            return credits.getCrew().stream()
                    .filter(c -> "Director".equals(c.getJob()))
                    .map(CrewMember::getName)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    public String getActors() {
        if (credits != null && credits.getCast() != null) {
            return credits.getCast().stream()
                    .sorted((a, b) -> Integer.compare(
                            a.getOrder() != null ? a.getOrder() : Integer.MAX_VALUE,
                            b.getOrder() != null ? b.getOrder() : Integer.MAX_VALUE))
                    .limit(5)
                    .map(CastMember::getName)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        return null;
    }

    public String getGenres() {
        if (genres != null) {
            return genres.stream()
                    .map(Genre::getName)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Genre {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("name")
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Credits {
        @JsonProperty("cast")
        private List<CastMember> cast;

        @JsonProperty("crew")
        private List<CrewMember> crew;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CastMember {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("character")
        private String character;

        @JsonProperty("profile_path")
        private String profilePath;

        @JsonProperty("order")
        private Integer order;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrewMember {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("job")
        private String job;

        @JsonProperty("department")
        private String department;
    }
}
