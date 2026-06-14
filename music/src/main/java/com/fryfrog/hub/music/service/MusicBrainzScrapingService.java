package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.dto.MusicBrainzResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@Slf4j
public class MusicBrainzScrapingService {

    private static final String MUSICBRAINZ_API_URL = "https://musicbrainz.org/ws/2";
    private static final String COVER_ART_API_URL = "https://coverartarchive.org";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public MusicBrainzScrapingService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public List<MusicBrainzResult> searchByTitleAndArtist(String title, String artist) {
        try {
            String query = String.format("recording:\"%s\" AND artist:\"%s\"", title, artist);
            String url = String.format("%s/recording?query=%s&fmt=json&limit=5", MUSICBRAINZ_API_URL, query);

            String response = executeGet(url);
            if (response == null || response.isBlank()) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode recordings = root.path("recordings");

            if (recordings == null || !recordings.isArray()) {
                return Collections.emptyList();
            }

            List<MusicBrainzResult> results = new ArrayList<>();
            for (JsonNode recording : recordings) {
                MusicBrainzResult result = parseRecording(recording);
                if (result != null) {
                    results.add(result);
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to search MusicBrainz for: {} - {}", title, artist, e);
            return Collections.emptyList();
        }
    }

    public MusicBrainzResult searchExact(String title, String artist) {
        List<MusicBrainzResult> results = searchByTitleAndArtist(title, artist);
        return results.isEmpty() ? null : results.get(0);
    }

    public String getCoverArt(String mbid) {
        try {
            String url = String.format("%s/release-group/%s", COVER_ART_API_URL, mbid);
            String response = executeGet(url);
            if (response == null || response.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode images = root.path("images");

            if (images != null && images.isArray() && !images.isEmpty()) {
                JsonNode firstImage = images.get(0);
                return firstImage.path("image").asText(null);
            }
        } catch (Exception e) {
            log.warn("Failed to get cover art for MBID: {}", mbid, e);
        }
        return null;
    }

    private MusicBrainzResult parseRecording(JsonNode recording) {
        try {
            String id = recording.path("id").asText(null);
            String title = recording.path("title").asText(null);

            // Get artist
            String artist = null;
            JsonNode relations = recording.path("relations");
            if (relations != null && relations.isArray()) {
                for (JsonNode relation : relations) {
                    JsonNode artistNode = relation.path("artist");
                    if (artistNode != null && !artistNode.isMissingNode()) {
                        artist = artistNode.path("name").asText(null);
                        break;
                    }
                }
            }

            // Get release info
            String album = null;
            Integer year = null;
            String releaseGroupId = null;

            for (JsonNode relation : relations) {
                JsonNode releaseNode = relation.path("release");
                if (releaseNode != null && !releaseNode.isMissingNode()) {
                    album = releaseNode.path("title").asText(null);

                    JsonNode dateNode = releaseNode.path("date");
                    if (dateNode != null && !dateNode.isNull()) {
                        String date = dateNode.asText("");
                        if (date.length() >= 4) {
                            year = Integer.parseInt(date.substring(0, 4));
                        }
                    }
                    break;
                }
            }

            // Get duration
            Long duration = null;
            JsonNode lengthNode = recording.path("length");
            if (lengthNode != null && !lengthNode.isNull()) {
                duration = lengthNode.asLong() / 1000; // Convert ms to seconds
            }

            MusicBrainzResult result = new MusicBrainzResult();
            result.setId(id);
            result.setTitle(title);
            result.setArtist(artist);
            result.setAlbum(album);
            result.setYear(year);
            result.setDuration(duration);
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse MusicBrainz recording", e);
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