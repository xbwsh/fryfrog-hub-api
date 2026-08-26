package com.fryfrog.hub.video.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbPersonDetail {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("also_known_as")
    private List<String> alsoKnownAs;

    @JsonProperty("biography")
    private String biography;

    @JsonProperty("birthday")
    private String birthday;

    @JsonProperty("deathday")
    private String deathday;

    @JsonProperty("gender")
    private Integer gender;

    @JsonProperty("homepage")
    private String homepage;

    @JsonProperty("imdb_id")
    private String imdbId;

    @JsonProperty("known_for_department")
    private String knownForDepartment;

    @JsonProperty("place_of_birth")
    private String placeOfBirth;

    @JsonProperty("popularity")
    private Double popularity;

    @JsonProperty("profile_path")
    private String profilePath;

    @JsonProperty("adult")
    private Boolean adult;

    @JsonProperty("combined_credits")
    private Credits combinedCredits;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Credits {
        @JsonProperty("cast")
        private List<Credit> cast;

        @JsonProperty("crew")
        private List<Credit> crew;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Credit {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("media_type")
        private String mediaType;

        @JsonProperty("title")
        private String title;

        @JsonProperty("name")
        private String name;

        @JsonProperty("original_title")
        private String originalTitle;

        @JsonProperty("original_name")
        private String originalName;

        @JsonProperty("character")
        private String character;

        @JsonProperty("job")
        private String job;

        @JsonProperty("department")
        private String department;

        @JsonProperty("release_date")
        private String releaseDate;

        @JsonProperty("first_air_date")
        private String firstAirDate;

        @JsonProperty("poster_path")
        private String posterPath;

        @JsonProperty("overview")
        private String overview;

        @JsonProperty("vote_average")
        private Double voteAverage;

        @JsonProperty("vote_count")
        private Integer voteCount;

        @JsonProperty("episode_count")
        private Integer episodeCount;

        @JsonProperty("adult")
        private Boolean adult;

        /** 电影用 title，剧集用 name */
        public String getDisplayTitle() {
            return title != null ? title : name;
        }

        public String getDisplayOriginalTitle() {
            return originalTitle != null ? originalTitle : originalName;
        }

        /** 电影用 release_date，剧集用 first_air_date */
        public String getDisplayDate() {
            return releaseDate != null ? releaseDate : firstAirDate;
        }

        public Integer getYear() {
            String d = getDisplayDate();
            if (d != null && d.length() >= 4) {
                try {
                    return Integer.parseInt(d.substring(0, 4));
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }
    }
}