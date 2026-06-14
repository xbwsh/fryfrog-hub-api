package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.dto.LyricsResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@Slf4j
public class LyricsScrapingService {

    private static final String LRCLIB_API_URL = "https://lrclib.net/api";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LyricsScrapingService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public LyricsResult searchByTitleAndArtist(String title, String artist) {
        try {
            String url = String.format("%s/get?artist_name=%s&track_name=%s", LRCLIB_API_URL, artist, title);
            String response = executeGet(url);

            if (response == null || response.isBlank()) {
                // Try search endpoint
                url = String.format("%s/search?artist_name=%s&track_name=%s", LRCLIB_API_URL, artist, title);
                response = executeGet(url);

                if (response == null || response.isBlank()) {
                    return null;
                }

                // Parse search results
                JsonNode root = objectMapper.readTree(response);
                if (root.isArray() && !root.isEmpty()) {
                    return parseLyrics(root.get(0));
                }
                return null;
            }

            // Parse direct result
            JsonNode root = objectMapper.readTree(response);
            return parseLyrics(root);
        } catch (Exception e) {
            log.error("Failed to search lyrics for: {} - {}", title, artist, e);
            return null;
        }
    }

    public LyricsResult searchExact(String title, String artist) {
        return searchByTitleAndArtist(title, artist);
    }

    private LyricsResult parseLyrics(JsonNode node) {
        try {
            String artist = node.path("artistName").asText(null);
            String title = node.path("trackName").asText(null);
            String lyrics = node.path("plainLyrics").asText(null);
            String language = node.path("language").asText(null);

            if (lyrics == null || lyrics.isBlank()) {
                lyrics = node.path("syncedLyrics").asText(null);
            }

            if (lyrics == null || lyrics.isBlank()) {
                return null;
            }

            LyricsResult result = new LyricsResult();
            result.setArtist(artist);
            result.setTitle(title);
            result.setLyrics(lyrics);
            result.setLanguage(language);
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse lyrics", e);
            return null;
        }
    }

    private String executeGet(String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/1.0 (https://github.com/fryfrog-hub)");
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("Failed to execute GET request to: {}", url, e);
        }
        return null;
    }
}