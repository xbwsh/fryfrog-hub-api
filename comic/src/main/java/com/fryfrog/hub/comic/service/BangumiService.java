package com.fryfrog.hub.comic.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@Slf4j
public class BangumiService {

    private static final String BASE_URL = "https://api.bgm.tv";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public BangumiService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public List<SearchResult> searchManga(String query) {
        if (query == null || query.isBlank()) return List.of();

        try {
            String url = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(BASE_URL + "/search/subject/{query}")
                    .queryParam("responseGroup", "large")
                    .queryParam("max_results", 10)
                    .queryParam("type", 1)
                    .buildAndExpand(query)
                    .toUriString();

            String body = httpGetWithRetry(url);
            if (body == null) return List.of();

            var root = objectMapper.readTree(body);
            var list = root.path("list");
            if (list.isArray()) {
                java.util.List<SearchResult> results = objectMapper.convertValue(list,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, SearchResult.class));
                log.info("Bangumi search for '{}' returned {} results", query, results.size());
                return results;
            }
        } catch (Exception e) {
            log.error("Failed to search manga on Bangumi: {}", e.getMessage(), e);
        }
        return List.of();
    }

    public SubjectDetail getSubjectDetail(Integer subjectId) {
        if (subjectId == null) return null;

        String url = BASE_URL + "/v0/subjects/" + subjectId;
        String body = httpGetWithRetry(url);
        if (body == null) return null;

        try {
            return objectMapper.readValue(body, SubjectDetail.class);
        } catch (Exception e) {
            log.error("Failed to parse Bangumi subject detail: id={}: {}", subjectId, e.getMessage(), e);
        }
        return null;
    }

    public List<RelatedSubject> getRelatedSubjects(Integer subjectId) {
        if (subjectId == null) return List.of();

        String url = BASE_URL + "/v0/subjects/" + subjectId + "/subjects";
        String body = httpGetWithRetry(url);
        if (body == null) return List.of();

        try {
            return objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RelatedSubject.class));
        } catch (Exception e) {
            log.error("Failed to parse Bangumi related subjects: id={}: {}", subjectId, e.getMessage(), e);
        }
        return List.of();
    }

    public List<Episode> getEpisodes(Integer subjectId) {
        if (subjectId == null) return List.of();

        String url = BASE_URL + "/v0/episodes?subject_id=" + subjectId + "&limit=200";
        String body = httpGetWithRetry(url);
        if (body == null) return List.of();

        try {
            var root = objectMapper.readTree(body);
            var data = root.path("data");
            if (data.isArray()) {
                return objectMapper.convertValue(data,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Episode.class));
            }
        } catch (Exception e) {
            log.error("Failed to parse Bangumi episodes: subjectId={}: {}", subjectId, e.getMessage(), e);
        }
        return List.of();
    }

    private String httpGetWithRetry(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "FryfrogHub/0.1.0");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody();
                }
                log.warn("Bangumi returned status {} for {}, attempt {}/3", response.getStatusCode(), url, attempt);
            } catch (Exception e) {
                log.warn("Bangumi request failed: {}, attempt {}/3: {}", url, attempt, e.getMessage());
            }
            if (attempt < 3) {
                try { Thread.sleep(500 * attempt); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchResult {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("url")
        private String url;

        @JsonProperty("type")
        private Integer type;

        @JsonProperty("name")
        private String name;

        @JsonProperty("name_cn")
        private String nameCn;

        @JsonProperty("summary")
        private String summary;

        @JsonProperty("air_date")
        private String airDate;

        @JsonProperty("rating")
        private Rating rating;

        @JsonProperty("rank")
        private Integer rank;

        @JsonProperty("images")
        private Image images;

        @JsonProperty("tags")
        private List<Tag> tags;

        public Double getScore() {
            return rating != null ? rating.getScore() : null;
        }

        public String getDate() {
            return airDate;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Rating {
            @JsonProperty("score")
            private Double score;

            @JsonProperty("total")
            private Integer total;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Image {
            @JsonProperty("large")
            private String large;

            @JsonProperty("common")
            private String common;

            @JsonProperty("medium")
            private String medium;

            @JsonProperty("small")
            private String small;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Tag {
            @JsonProperty("name")
            private String name;

            @JsonProperty("count")
            private Integer count;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubjectDetail {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("name_cn")
        private String nameCn;

        @JsonProperty("summary")
        private String summary;

        @JsonProperty("air_date")
        private String airDate;

        @JsonProperty("rating")
        private SearchResult.Rating rating;

        @JsonProperty("rank")
        private Integer rank;

        @JsonProperty("total_episodes")
        private Integer totalEpisodes;

        @JsonProperty("images")
        private SearchResult.Image images;

        @JsonProperty("tags")
        private List<SearchResult.Tag> tags;

        @JsonProperty("infobox")
        private List<InfoboxEntry> infobox;

        public Double getScore() {
            return rating != null ? rating.getScore() : null;
        }

        public String getDate() {
            return airDate;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class InfoboxEntry {
            @JsonProperty("key")
            private String key;

            @JsonProperty("value")
            private Object value;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Episode {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("sort")
        private Integer sort;

        @JsonProperty("name")
        private String name;

        @JsonProperty("name_cn")
        private String nameCn;

        @JsonProperty("duration")
        private String duration;

        @JsonProperty("airdate")
        private String airdate;

        @JsonProperty("comment")
        private Integer comment;

        @JsonProperty("info")
        private String info;

        @JsonProperty("disc")
        private Integer disc;

        @JsonProperty("image")
        private String image;

        public String getDisplayName() {
            if (nameCn != null && !nameCn.isBlank()) return nameCn;
            if (name != null && !name.isBlank()) return name;
            return "第" + sort + "卷";
        }

        public String getCoverUrl() {
            return image;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelatedSubject {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("type")
        private Integer type;

        @JsonProperty("name")
        private String name;

        @JsonProperty("name_cn")
        private String nameCn;

        @JsonProperty("images")
        private SearchResult.Image images;

        public String getCoverUrl() {
            if (images == null) return null;
            String url = images.getLarge();
            if (url == null || url.isBlank()) url = images.getCommon();
            return url;
        }
    }
}
