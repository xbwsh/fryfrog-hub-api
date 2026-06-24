package com.fryfrog.hub.comic.dto.anilist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnilistSearchResult {

    @JsonProperty("data")
    private DataWrapper data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataWrapper {
        @JsonProperty("Page")
        private Page page;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {
        @JsonProperty("media")
        private List<MediaItem> media;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaItem {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("title")
        private MediaTitle title;

        @JsonProperty("coverImage")
        private CoverImage coverImage;

        @JsonProperty("description")
        private String description;

        @JsonProperty("meanScore")
        private Integer meanScore;

        @JsonProperty("genres")
        private List<String> genres;

        @JsonProperty("startDate")
        private StartDate startDate;

        @JsonProperty("volumes")
        private Integer volumes;

        @JsonProperty("status")
        private String status;

        @JsonProperty("type")
        private String type;

        @JsonProperty("staff")
        private StaffWrapper staff;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class MediaTitle {
            @JsonProperty("romaji")
            private String romaji;

            @JsonProperty("english")
            private String english;

            @JsonProperty("native")
            private String nativeTitle;

            public String getBestTitle() {
                if (english != null && !english.isBlank()) return english;
                if (romaji != null && !romaji.isBlank()) return romaji;
                return nativeTitle;
            }
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class CoverImage {
            @JsonProperty("large")
            private String large;

            @JsonProperty("medium")
            private String medium;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class StartDate {
            @JsonProperty("year")
            private Integer year;

            @JsonProperty("month")
            private Integer month;

            @JsonProperty("day")
            private Integer day;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class StaffWrapper {
            @JsonProperty("edges")
            private List<StaffEdge> edges;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class StaffEdge {
            @JsonProperty("node")
            private StaffNode node;

            @JsonProperty("role")
            private String role;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class StaffNode {
            @JsonProperty("name")
            private StaffName name;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class StaffName {
            @JsonProperty("full")
            private String full;
        }
    }
}
