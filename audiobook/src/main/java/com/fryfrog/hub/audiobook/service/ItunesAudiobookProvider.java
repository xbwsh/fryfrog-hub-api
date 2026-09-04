package com.fryfrog.hub.audiobook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Apple iTunes Search API 有声书源（官方公开接口，无需 Key）。
 * 接口：https://itunes.apple.com/search?media=audiobook&term=...
 * 覆盖以出版书有声版为主，网文有声书覆盖有限。
 */
@Component
@Slf4j
public class ItunesAudiobookProvider implements AudiobookMetadataProvider {

    private static final String SEARCH_URL =
            "https://itunes.apple.com/search?media=audiobook&limit=20&term=";
    private static final String LOOKUP_URL =
            "https://itunes.apple.com/lookup?id=";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public ItunesAudiobookProvider(@Qualifier("scraperRestTemplate") RestTemplate scraperRestTemplate) {
        this.restTemplate = scraperRestTemplate;
    }

    @Override
    public String source() {
        return "itunes";
    }

    @Override
    public String displayName() {
        return "Apple Books";
    }

    @Override
    public List<AudiobookScrapeResult> search(String keyword) throws Exception {
        String url = SEARCH_URL + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String body = restTemplate.getForObject(url, String.class);
        if (body == null || body.isBlank()) return List.of();

        JsonNode results = mapper.readTree(body).path("results");
        List<AudiobookScrapeResult> list = new ArrayList<>();
        for (JsonNode node : results) {
            AudiobookScrapeResult r = new AudiobookScrapeResult();
            r.setSource(source());
            r.setSourceId(node.path("trackId").asText());
            r.setTitle(node.path("trackName").asText(null));
            r.setAuthor(node.path("artistName").asText(null));
            r.setNarrator(null); // iTunes 无朗读者字段
            r.setOverview(node.path("description").asText(
                    node.path("longDescription").asText(null)));
            String artwork = node.path("artworkUrl100").asText(null);
            if (artwork != null) {
                r.setCoverUrl(artwork.replace("100x100", "600x600"));
            }
            r.setSeries(node.path("collectionName").asText(null));
            String date = node.path("releaseDate").asText("");
            if (date.length() >= 4) {
                try {
                    r.setYear(Integer.parseInt(date.substring(0, 4)));
                } catch (NumberFormatException ignored) {
                }
            }
            list.add(r);
        }
        log.info("[AudiobookScrape] iTunes search '{}': {} results", keyword, list.size());
        return list;
    }

    @Override
    public AudiobookScrapeResult fetch(String sourceId) throws Exception {
        String body = restTemplate.getForObject(LOOKUP_URL + sourceId, String.class);
        if (body == null || body.isBlank()) return null;
        JsonNode results = mapper.readTree(body).path("results");
        if (!results.isArray() || results.isEmpty()) return null;
        JsonNode node = results.get(0);
        AudiobookScrapeResult r = new AudiobookScrapeResult();
        r.setSource(source());
        r.setSourceId(node.path("trackId").asText());
        r.setTitle(node.path("trackName").asText(null));
        r.setAuthor(node.path("artistName").asText(null));
        r.setOverview(node.path("description").asText(
                node.path("longDescription").asText(null)));
        String artwork = node.path("artworkUrl100").asText(null);
        if (artwork != null) {
            r.setCoverUrl(artwork.replace("100x100", "600x600"));
        }
        r.setSeries(node.path("collectionName").asText(null));
        return r;
    }
}
