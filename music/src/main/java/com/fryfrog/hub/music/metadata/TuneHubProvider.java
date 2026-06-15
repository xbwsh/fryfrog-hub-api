package com.fryfrog.hub.music.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.music.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
@Slf4j
public class TuneHubProvider implements MusicMetadataProvider {

    @Value("${hub.music.api-provider:tunehub}")
    private String apiProvider;

    @Value("${hub.music.tunehub.base-url:https://music-dl.sayqz.com}")
    private String baseUrl;

    @Value("${hub.music.platforms:netease,kuwo,qq}")
    private String platformsConfig;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public TuneHubProvider(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "TuneHub";
    }

    @Override
    public boolean isAvailable() {
        return "tunehub".equalsIgnoreCase(apiProvider);
    }

    @Override
    public List<SearchResult> search(String artist, String title, String album) {
        String keyword = String.format("%s %s", artist, title).trim();
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String url = String.format("%s/api/?type=aggregateSearch&keyword=%s",
                    baseUrl, java.net.URLEncoder.encode(keyword, "UTF-8"));

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            int code = root.path("code").asInt(0);
            if (code != 200) {
                log.warn("TuneHub returned non-200 code: {}", code);
                return Collections.emptyList();
            }

            JsonNode results = root.path("data").path("results");
            if (results == null || !results.isArray()) {
                return Collections.emptyList();
            }

            List<SearchResult> searchResults = new ArrayList<>();
            for (JsonNode item : results) {
                Map<String, Object> map = objectMapper.convertValue(item, Map.class);
                searchResults.add(SearchResult.fromDict(map));
            }

            log.info("TuneHub found {} results for: {} - {}", searchResults.size(), artist, title);
            return searchResults;

        } catch (Exception e) {
            log.error("TuneHub search failed for '{}' - '{}': {}", artist, title, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String getLyrics(SearchResult result) {
        if (result.getLrcUrl() == null || result.getLrcUrl().isBlank()) {
            return null;
        }

        try {
            log.info("Fetching lyrics from TuneHub/{}: {}", result.getPlatform(), result.getName());
            ResponseEntity<String> response = restTemplate.getForEntity(result.getLrcUrl(), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }

            String content = response.getBody();
            if (content.startsWith("{")) {
                return null;
            }
            return content;

        } catch (Exception e) {
            log.warn("Failed to get lyrics from TuneHub for {}: {}", result.getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] getCover(SearchResult result) {
        if (result.getPicUrl() == null || result.getPicUrl().isBlank()) {
            return null;
        }

        try {
            log.info("Fetching cover from TuneHub/{}: {}", result.getPlatform(), result.getName());
            ResponseEntity<byte[]> response = restTemplate.getForEntity(result.getPicUrl(), byte[].class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }

            byte[] data = response.getBody();
            String contentType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString() : "";
            if (contentType.contains("image") || data.length > 1000) {
                return data;
            }
            return null;

        } catch (Exception e) {
            log.warn("Failed to get cover from TuneHub for {}: {}", result.getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public SearchResult findBestMatch(List<SearchResult> results, String artist, String title) {
        if (results.isEmpty()) {
            return null;
        }

        String artistLower = artist != null ? artist.toLowerCase() : "";
        String titleLower = title != null ? title.toLowerCase() : "";

        List<String> platformPriority = Arrays.asList(platformsConfig.split(","));

        SearchResult best = null;
        int bestScore = -1;

        for (SearchResult r : results) {
            int score = 0;
            String rName = r.getName() != null ? r.getName().toLowerCase() : "";
            String rArtist = r.getArtist() != null ? r.getArtist().toLowerCase() : "";

            if (rName.equals(titleLower)) {
                score += 100;
            } else if (rName.contains(titleLower)) {
                score += 50;
            }

            if (!artistLower.isEmpty() && (rArtist.contains(artistLower) || artistLower.contains(rArtist))) {
                score += 30;
            }

            int platformIdx = platformPriority.indexOf(r.getPlatform());
            if (platformIdx >= 0) {
                score += (10 - platformIdx);
            }

            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }

        if (bestScore >= 30) {
            log.info("Best match (score={}): {} - {} [{}]", bestScore, best.getArtist(), best.getName(), best.getPlatform());
            return best;
        }

        log.warn("No good match found for {} - {} (best score={})", artist, title, bestScore);
        return null;
    }
}
