package com.fryfrog.hub.music.metadata;

import com.fryfrog.hub.music.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class LrcCxProvider implements MusicMetadataProvider {

    private static final String BASE_URL = "https://api.lrc.cx";

    private final RestTemplate restTemplate;

    @Value("${hub.proxy.host:}")
    private String proxyHost;

    @Value("${hub.proxy.port:0}")
    private int proxyPort;

    public LrcCxProvider(@Qualifier("restTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String getName() {
        return "LrcCx";
    }

    @Override
    public boolean isAvailable() {
        return proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;
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
        synthetic.setPlatform("lrccx");

        return List.of(synthetic);
    }

    @Override
    public String getLyrics(SearchResult result) {
        try {
            StringBuilder urlBuilder = new StringBuilder(BASE_URL).append("/lyrics?");
            appendParam(urlBuilder, "title", result.getName());
            appendParam(urlBuilder, "artist", result.getArtist());

            log.info("LrcCx fetching lyrics for: {} - {}", result.getArtist(), result.getName());
            ResponseEntity<String> response = restTemplate.exchange(
                    urlBuilder.toString(), HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }

            String content = response.getBody();
            if (content.contains("[") && !content.startsWith("{")) {
                log.info("LrcCx got lyrics for: {}", result.getName());
                return content;
            }
            return null;

        } catch (Exception e) {
            log.warn("Failed to get lyrics from LrcCx for {}: {}", result.getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] getCover(SearchResult result) {
        return null;
    }

    @Override
    public SearchResult findBestMatch(List<SearchResult> results, String artist, String title) {
        return results.isEmpty() ? null : results.get(0);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        return headers;
    }

    private void appendParam(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.charAt(sb.length() - 1) != '?') {
                sb.append("&");
            }
            sb.append(key).append("=").append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }
}
