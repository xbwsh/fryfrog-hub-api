package com.fryfrog.hub.common.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BangumiService {

    private static final String BASE_URL = "https://api.bgm.tv";
    private static final Pattern VOLUME_NAME_PATTERN_1 = Pattern.compile("\\((\\d+)\\)");
    private static final Pattern VOLUME_NAME_PATTERN_2 = Pattern.compile("(?i)vol\\.?\\s*(\\d+)");
    private static final Pattern VOLUME_NAME_PATTERN_3 = Pattern.compile("第\\s*(\\d+)\\s*卷");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public BangumiService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用 Bangumi v0 API 搜索书籍（type=1）
     */
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) return List.of();

        try {
            String url = BASE_URL + "/v0/search/subjects";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "FryfrogHub/0.1.0");

            String bodyJson = "{\"keyword\":\"" + query.replace("\"", "\\\"") + "\",\"filter\":{\"type\":[1]}}";
            HttpEntity<String> request = new HttpEntity<>(bodyJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Bangumi v0 search returned status {} for '{}'", response.getStatusCode(), query);
                return List.of();
            }

            var root = objectMapper.readTree(response.getBody());
            var data = root.path("data");
            if (data.isArray()) {
                List<SearchResult> results = objectMapper.convertValue(data,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, SearchResult.class));
                log.info("Bangumi search for '{}' returned {} results", query, results.size());
                return results;
            }
        } catch (Exception e) {
            log.error("Failed to search on Bangumi: {}", e.getMessage(), e);
        }
        return List.of();
    }

    /**
     * 搜索书籍并按子类型过滤
     * @param subType "novel"=轻小说/小说, "manga"=漫画, null=不过滤
     */
    public List<SearchResult> searchBooks(String query, String subType) {
        List<SearchResult> all = search(query);
        if (subType == null || subType.isBlank()) return all;

        List<SearchResult> filtered = all.stream()
                .filter(r -> matchSubType(r, subType))
                .collect(Collectors.toList());

        log.info("Bangumi searchBooks '{}' subType='{}': {} total, {} matched",
                query, subType, all.size(), filtered.size());
        if (!all.isEmpty()) {
            log.info("All candidates: {}",
                    all.stream().limit(8)
                            .map(r -> r.getId() + ":" + r.getNameCn() + "/" + r.getName()
                                    + " [platform=" + r.getPlatform() + ", tags=" + formatTags(r.getTags()) + "]"
                                    + (matchSubType(r, subType) ? " ✓" : " ✗"))
                            .toList());
        }

        return filtered;
    }

    private boolean matchSubType(SearchResult r, String subType) {
        // platform 字段最可靠，有 platform 时只用 platform 判断
        if (r.getPlatform() != null) {
            if ("novel".equalsIgnoreCase(subType)) {
                return r.getPlatform().contains("小说") || r.getPlatform().equalsIgnoreCase("novel");
            } else if ("manga".equalsIgnoreCase(subType)) {
                return r.getPlatform().contains("漫画");
            }
            return false;
        }

        // platform 为空时回退到 tags
        if (r.getTags() != null) {
            if ("novel".equalsIgnoreCase(subType)) {
                return r.getTags().stream().anyMatch(t ->
                        "轻小说".equals(t.getName()) || "小说".equals(t.getName()));
            } else if ("manga".equalsIgnoreCase(subType)) {
                return r.getTags().stream().anyMatch(t ->
                        "漫画".equals(t.getName()));
            }
        }
        return false;
    }

    private String formatTags(List<SearchResult.Tag> tags) {
        if (tags == null) return "null";
        return tags.stream().map(SearchResult.Tag::getName).collect(Collectors.joining(","));
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

    public List<Character> getCharacters(Integer subjectId) {
        if (subjectId == null) return List.of();

        String url = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(BASE_URL + "/v0/subjects/" + subjectId + "/characters")
                .queryParam("lang", "zh")
                .toUriString();
        String body = httpGetWithRetry(url);
        if (body == null) return List.of();

        try {
            return objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Character.class));
        } catch (Exception e) {
            log.error("Failed to parse Bangumi characters: subjectId={}: {}", subjectId, e.getMessage(), e);
        }
        return List.of();
    }

    public CharacterDetail getCharacterDetail(Integer characterId) {
        if (characterId == null) return null;

        String url = BASE_URL + "/v0/characters/" + characterId;
        String body = httpGetWithRetry(url);
        if (body == null) return null;

        try {
            return objectMapper.readValue(body, CharacterDetail.class);
        } catch (Exception e) {
            log.error("Failed to parse Bangumi character detail: id={}: {}", characterId, e.getMessage(), e);
        }
        return null;
    }

    // ==================== 共享工具方法 ====================

    /**
     * 按评分人数降序排列搜索结果
     */
    public static List<SearchResult> sortByPopularity(List<SearchResult> results) {
        List<SearchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.<SearchResult>comparingInt(r -> {
            SearchResult.Rating rating = r.getRating();
            return rating != null && rating.getTotal() != null ? rating.getTotal() : 0;
        }).reversed());
        return sorted;
    }

    /**
     * 从 tags 中提取前5个作为 genre 字符串
     */
    public static String extractGenresFromTags(List<SearchResult.Tag> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return tags.stream()
                .map(SearchResult.Tag::getName)
                .filter(Objects::nonNull)
                .limit(5)
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    /**
     * 从 SubjectDetail 的 infobox 中提取指定 key 的值
     */
    public static String extractInfoboxValue(SubjectDetail detail, String key) {
        if (detail.getInfobox() == null) return null;
        return detail.getInfobox().stream()
                .filter(e -> key.equals(e.getKey()))
                .map(e -> String.valueOf(e.getValue()))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * 从关联条目名称中提取卷号
     */
    public static Integer extractVolumeFromRelatedName(String name) {
        Matcher m = VOLUME_NAME_PATTERN_1.matcher(name);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = VOLUME_NAME_PATTERN_2.matcher(name);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = VOLUME_NAME_PATTERN_3.matcher(name);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    /**
     * 获取关联条目并构建卷号→条目映射
     */
    public Map<Integer, RelatedSubject> buildVolumeSubjectMap(Integer subjectId) {
        Map<Integer, RelatedSubject> map = new HashMap<>();
        List<RelatedSubject> related = getRelatedSubjects(subjectId);
        for (RelatedSubject sub : related) {
            if (sub.getType() == null || sub.getType() != 1) continue;
            String name = sub.getName();
            if (name == null) continue;
            Integer vol = extractVolumeFromRelatedName(name);
            if (vol != null) map.put(vol, sub);
        }
        log.info("Found {} volume subjects from related subjects for Bangumi id={}", map.size(), subjectId);
        return map;
    }

    /**
     * 下载 Bangumi 封面到指定目录
     * @return 本地文件路径，失败返回 null
     */
    public String downloadCover(SubjectDetail detail, String filePrefix, Path targetDir) {
        String coverUrl = null;
        if (detail.getImages() != null) {
            coverUrl = detail.getImages().getLarge();
            if (coverUrl == null || coverUrl.isBlank()) {
                coverUrl = detail.getImages().getCommon();
            }
        }
        return downloadCover(coverUrl, filePrefix, targetDir);
    }

    /**
     * 下载指定 URL 的封面到指定目录
     * @return 本地文件路径，失败返回 null
     */
    public String downloadCover(String coverUrl, String filePrefix, Path targetDir) {
        if (coverUrl == null || coverUrl.isBlank()) return null;

        try {
            Files.createDirectories(targetDir);
            Path coverPath = targetDir.resolve(filePrefix + "_cover.jpg");
            if (Files.exists(coverPath)) {
                return coverPath.toAbsolutePath().toString();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/0.1.0");
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    coverUrl, HttpMethod.GET, request, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Files.write(coverPath, response.getBody());
                log.info("Downloaded Bangumi cover to {}", coverPath);
                return coverPath.toAbsolutePath().toString();
            }
        } catch (IOException e) {
            log.warn("Failed to download Bangumi cover: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 内部方法 ====================

    private String httpGetWithRetry(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "FryfrogHub/0.1.0");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody();
                }
                log.warn("Bangumi returned status {} for {}, attempt {}/5", response.getStatusCode(), url, attempt);
            } catch (Exception e) {
                log.warn("Bangumi request failed: {}, attempt {}/5: {}", url, attempt, e.getMessage());
            }
            if (attempt < 5) {
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }
        return null;
    }

    // ==================== DTO ====================

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

        @JsonProperty("platform")
        private String platform;

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

        @JsonProperty("platform")
        private String platform;

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

        public boolean isNovel() {
            // 1. 优先用 platform 字段（"小说"=轻小说/小说）
            if (platform != null && (platform.contains("小说") || platform.equalsIgnoreCase("novel"))) {
                return true;
            }
            // 2. 检查 tags
            if (tags != null) {
                boolean hasNovelTag = tags.stream().anyMatch(t ->
                        "轻小说".equals(t.getName()) || "小说".equals(t.getName()));
                if (hasNovelTag) return true;
            }
            // 3. 检查 infobox 中的 "文库" 或 "书籍" 信息
            if (infobox != null) {
                boolean hasNovelInfo = infobox.stream().anyMatch(e ->
                        "文库".equals(e.getKey()) || "出版社".equals(e.getKey()));
                if (hasNovelInfo) return true;
            }
            return false;
        }

        public boolean isManga() {
            // 1. 优先用 platform 字段（"漫画"）
            if (platform != null && platform.contains("漫画")) {
                return true;
            }
            // 2. 检查 tags
            if (tags != null) {
                boolean hasMangaTag = tags.stream().anyMatch(t ->
                        "漫画".equals(t.getName()));
                if (hasMangaTag) return true;
            }
            return false;
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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Character {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("name_cn")
        private String nameCn;

        @JsonProperty("summary")
        private String summary;

        @JsonProperty("images")
        private SearchResult.Image images;

        @JsonProperty("role")
        private String role;

        public String getDisplayName() {
            if (nameCn != null && !nameCn.isBlank()) return nameCn;
            return name;
        }

        public String getImageUrl() {
            if (images == null) return null;
            String url = images.getLarge();
            if (url == null || url.isBlank()) url = images.getCommon();
            return url;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CharacterDetail {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("summary")
        private String summary;

        @JsonProperty("images")
        private SearchResult.Image images;

        @JsonProperty("infobox")
        private List<InfoboxEntry> infobox;

        public String getChineseName() {
            if (infobox == null) return null;
            for (InfoboxEntry entry : infobox) {
                if ("简体中文名".equals(entry.getKey()) || "中文名".equals(entry.getKey())) {
                    String val = entry.getValueAsString();
                    if (val != null && !val.isBlank()) return val;
                }
            }
            return null;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class InfoboxEntry {
            @JsonProperty("key")
            private String key;

            @JsonProperty("value")
            private Object value;

            public String getValueAsString() {
                if (value == null) return null;
                if (value instanceof String s) return s;
                return value.toString();
            }
        }
    }
}
