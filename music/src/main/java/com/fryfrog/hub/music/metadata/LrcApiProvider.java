package com.fryfrog.hub.music.metadata;

import com.fryfrog.hub.music.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class LrcApiProvider implements MusicMetadataProvider {

    @Value("${hub.music.api-provider:tunehub}")
    private String apiProvider;

    @Value("${hub.music.lrcapi.url:https://api.lrc.cx}")
    private String baseUrl;

    @Value("${hub.music.lrcapi.auth:}")
    private String authKey;

    private final RestTemplate restTemplate;

    public LrcApiProvider(@Qualifier("scraperRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String getName() {
        return "LrcApi";
    }

    @Override
    public boolean isAvailable() {
        return "lrcapi".equalsIgnoreCase(apiProvider);
    }

    @Override
    public List<SearchResult> search(String artist, String title, String album) {
        if (title == null || title.isBlank()) {
            return Collections.emptyList();
        }

        SearchResult synthetic = new SearchResult();
        synthetic.setId(String.format("%s_%s_%s", artist, title, album));
        synthetic.setName(title);
        synthetic.setArtist(artist != null ? artist : "");
        synthetic.setAlbum(album != null ? album : "");
        synthetic.setPlatform("lrcapi");
        synthetic.setLrcUrl("");
        synthetic.setPicUrl("");

        log.info("LrcApi search: {} - {}", artist, title);
        return List.of(synthetic);
    }

    @Override
    public String getLyrics(SearchResult result) {
        try {
            StringBuilder urlBuilder = new StringBuilder(baseUrl).append("/lyrics?");
            appendParam(urlBuilder, "title", result.getName());
            appendParam(urlBuilder, "album", result.getAlbum());
            appendParam(urlBuilder, "artist", result.getArtist());

            log.info("Fetching lyrics from LrcApi: {} - {}", result.getArtist(), result.getName());
            ResponseEntity<String> response = restTemplate.exchange(
                    urlBuilder.toString(), HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }

            String content = response.getBody();
            if (content.contains("[") && !content.startsWith("{")) {
                return content;
            }
            return null;

        } catch (Exception e) {
            log.warn("Failed to get lyrics from LrcApi for {}: {}", result.getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] getCover(SearchResult result) {
        try {
            StringBuilder urlBuilder = new StringBuilder(baseUrl).append("/cover?");
            appendParam(urlBuilder, "title", result.getName());
            appendParam(urlBuilder, "album", result.getAlbum());
            appendParam(urlBuilder, "artist", result.getArtist());

            log.info("Fetching cover from LrcApi: {} - {}", result.getArtist(), result.getName());
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    urlBuilder.toString(), HttpMethod.GET, new HttpEntity<>(buildHeaders()), byte[].class);

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
            log.warn("Failed to get cover from LrcApi for {}: {}", result.getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public SearchResult findBestMatch(List<SearchResult> results, String artist, String title) {
        return results.isEmpty() ? null : results.get(0);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
        headers.set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
        if (authKey != null && !authKey.isBlank()) {
            headers.set("Authorization", authKey);
        }
        return headers;
    }

    private void appendParam(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.charAt(sb.length() - 1) != '?') {
                sb.append("&");
            }
            sb.append(key).append("=").append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
