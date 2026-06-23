package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicTrack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
@Slf4j
public class MusicBrainzService {

    private static final String BASE_URL = "https://musicbrainz.org/ws/2";
    private static final String COVER_ART_URL = "https://coverartarchive.org";
    private static final String USER_AGENT = "FryfrogHub/1.0 ( https://github.com/xbwsh/fryfrog-hub-api )";

    private final RestTemplate restTemplate;

    public MusicBrainzService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map<String, String> searchRecording(String artist, String title, String album) {
        String query = String.format("recording:\"%s\" AND artist:\"%s\"", title, artist);
        if (album != null && !album.isBlank()) {
            query += String.format(" AND release:\"%s\"", album);
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/recording")
                .queryParam("query", query)
                .queryParam("fmt", "json")
                .queryParam("limit", "5")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> recordings = (List<Map>) response.getBody().get("recordings");
                if (recordings != null && !recordings.isEmpty()) {
                    Map recording = recordings.get(0);
                    Map<String, String> result = new HashMap<>();
                    result.put("id", (String) recording.get("id"));
                    result.put("title", (String) recording.get("title"));

                    List<Map> releases = (List<Map>) recording.get("releases");
                    if (releases != null && !releases.isEmpty()) {
                        Map release = releases.get(0);
                        result.put("releaseId", (String) release.get("id"));
                        result.put("releaseDate", (String) release.get("date"));

                        Map releaseGroup = (Map) release.get("release-group");
                        if (releaseGroup != null) {
                            List<Map> labels = (List<Map>) releaseGroup.get("label-info");
                            if (labels != null && !labels.isEmpty()) {
                                Map labelInfo = labels.get(0);
                                Map label = (Map) labelInfo.get("label");
                                if (label != null) {
                                    result.put("label", (String) label.get("name"));
                                    result.put("catalogNumber", (String) labelInfo.get("catalog-number"));
                                }
                            }
                        }
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("MusicBrainz search failed: {}", e.getMessage());
        }
        return null;
    }

    public String getCoverArtUrl(String releaseId) {
        String url = COVER_ART_URL + "/release/" + releaseId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        headers.set("Accept", "application/json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> images = (List<Map>) response.getBody().get("images");
                if (images != null && !images.isEmpty()) {
                    for (Map image : images) {
                        String type = (String) image.get("types");
                        if ("Front".equals(type)) {
                            return (String) image.get("image");
                        }
                    }
                    return (String) images.get(0).get("image");
                }
            }
        } catch (Exception e) {
            log.debug("Cover Art Archive lookup failed for release {}: {}", releaseId, e.getMessage());
        }
        return null;
    }

    public Map<String, String> getArtistInfo(String artistName) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/artist")
                .queryParam("query", artistName)
                .queryParam("fmt", "json")
                .queryParam("limit", "1")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> artists = (List<Map>) response.getBody().get("artists");
                if (artists != null && !artists.isEmpty()) {
                    Map artist = artists.get(0);
                    Map<String, String> result = new HashMap<>();
                    result.put("id", (String) artist.get("id"));
                    result.put("name", (String) artist.get("name"));

                    String type = (String) artist.get("type");
                    if ("Person".equals(type)) {
                        result.put("type", "person");
                    } else {
                        result.put("type", "group");
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("MusicBrainz artist search failed: {}", e.getMessage());
        }
        return null;
    }

    public void enrichTrack(MusicTrack track) {
        if (track.getArtist() == null || track.getTitle() == null) {
            return;
        }

        try {
            Map<String, String> recording = searchRecording(
                    track.getArtist(), track.getTitle(), track.getAlbum());
            if (recording != null) {
                track.setMusicBrainzId(recording.get("id"));
                if (track.getLabel() == null || track.getLabel().isBlank()) {
                    track.setLabel(recording.get("label"));
                }
                if (track.getCatalogNumber() == null || track.getCatalogNumber().isBlank()) {
                    track.setCatalogNumber(recording.get("catalogNumber"));
                }
                if (track.getReleaseDate() == null || track.getReleaseDate().isBlank()) {
                    track.setReleaseDate(recording.get("releaseDate"));
                }

                String releaseId = recording.get("releaseId");
                if (releaseId != null && (track.getCoverArtPath() == null || track.getCoverArtPath().isBlank())) {
                    String coverUrl = getCoverArtUrl(releaseId);
                    if (coverUrl != null) {
                        track.setCoverSource("musicbrainz");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich track {} - {}: {}", track.getArtist(), track.getTitle(), e.getMessage());
        }
    }
}
