package com.fryfrog.hub.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
@Slf4j
public class QQMusicService {

    private static final String SEARCH_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String LYRICS_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
    private static final String COVER_BASE_URL = "https://y.gtimg.cn/music/photo_new/";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public QQMusicService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String searchLyrics(String artist, String title) {
        try {
            String songMid = searchSongMid(artist, title);
            if (songMid == null) {
                return null;
            }
            return fetchLyrics(songMid);
        } catch (Exception e) {
            log.warn("Failed to search lyrics from QQ Music for {} - {}: {}", artist, title, e.getMessage());
            return null;
        }
    }

    private String searchSongMid(String artist, String title) {
        String keywords = artist + " " + title;

        HttpHeaders headers = createHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("comm", Map.of("ct", 19, "cv", 1859));
        body.put("req", Map.of(
                "method", "DoSearchForQQMusicDesktop",
                "module", "music.search.SearchCgiService",
                "param", Map.of(
                        "num_per_page", 5,
                        "page_num", 1,
                        "query", keywords,
                        "search_type", 0
                )
        ));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(SEARCH_URL, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode songList = root.path("req").path("data").path("body").path("song").path("list");
                if (songList.isArray() && !songList.isEmpty()) {
                    return songList.get(0).path("mid").asText(null);
                }
            }
        } catch (Exception e) {
            log.warn("QQ Music search failed: {}", e.getMessage());
        }
        return null;
    }

    private String fetchLyrics(String songMid) {
        String url = UriComponentsBuilder.fromHttpUrl(LYRICS_URL)
                .queryParam("songmid", songMid)
                .queryParam("format", "json")
                .queryParam("nobase64", "1")
                .toUriString();

        HttpHeaders headers = createHeaders();

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String lyrics = root.path("lyric").asText(null);
                if (lyrics != null && !lyrics.isBlank()) {
                    return lyrics;
                }
            }
        } catch (Exception e) {
            log.warn("QQ Music lyrics fetch failed: {}", e.getMessage());
        }
        return null;
    }

    public String searchCoverUrl(String artist, String title) {
        try {
            Map<String, Object> songInfo = searchSongInfoFull(artist, title);
            if (songInfo == null) {
                return null;
            }
            String albumMid = (String) songInfo.get("albumMid");
            if (albumMid != null && !albumMid.isBlank()) {
                return COVER_BASE_URL + "T002R300x300M000" + albumMid + ".jpg";
            }
        } catch (Exception e) {
            log.warn("Failed to search cover from QQ Music: {}", e.getMessage());
        }
        return null;
    }

    public String searchArtistImage(String artist) {
        try {
            String singerMid = searchSingerMid(artist);
            if (singerMid != null && !singerMid.isBlank()) {
                return COVER_BASE_URL + "T001R300x300M000" + singerMid + ".jpg";
            }
        } catch (Exception e) {
            log.warn("Failed to search artist image from QQ Music: {}", e.getMessage());
        }
        return null;
    }

    private String searchSingerMid(String artist) {
        HttpHeaders headers = createHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("comm", Map.of("ct", 19, "cv", 1859));
        body.put("req", Map.of(
                "method", "DoSearchForQQMusicDesktop",
                "module", "music.search.SearchCgiService",
                "param", Map.of(
                        "num_per_page", 1,
                        "page_num", 1,
                        "query", artist,
                        "search_type", 0
                )
        ));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(SEARCH_URL, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode songList = root.path("req").path("data").path("body").path("song").path("list");
                if (songList.isArray() && !songList.isEmpty()) {
                    JsonNode singers = songList.get(0).path("singer");
                    if (singers.isArray() && !singers.isEmpty()) {
                        return singers.get(0).path("mid").asText(null);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("QQ Music singer search failed: {}", e.getMessage());
        }
        return null;
    }

    public Map<String, String> searchSongInfo(String artist, String title) {
        Map<String, Object> fullInfo = searchSongInfoFull(artist, title);
        if (fullInfo == null) {
            return null;
        }

        Map<String, String> result = new HashMap<>();
        result.put("songmid", (String) fullInfo.get("mid"));
        result.put("songname", (String) fullInfo.get("name"));
        result.put("singer", (String) fullInfo.get("singer"));
        result.put("albumname", (String) fullInfo.get("albumName"));
        result.put("albummid", (String) fullInfo.get("albumMid"));
        result.put("interval", String.valueOf(fullInfo.get("interval")));
        return result;
    }

    private Map<String, Object> searchSongInfoFull(String artist, String title) {
        String keywords = artist + " " + title;

        HttpHeaders headers = createHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("comm", Map.of("ct", 19, "cv", 1859));
        body.put("req", Map.of(
                "method", "DoSearchForQQMusicDesktop",
                "module", "music.search.SearchCgiService",
                "param", Map.of(
                        "num_per_page", 5,
                        "page_num", 1,
                        "query", keywords,
                        "search_type", 0
                )
        ));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(SEARCH_URL, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode songList = root.path("req").path("data").path("body").path("song").path("list");
                if (songList.isArray() && !songList.isEmpty()) {
                    JsonNode songItem = songList.get(0);
                    Map<String, Object> result = new HashMap<>();
                    result.put("mid", songItem.path("mid").asText(null));
                    result.put("name", songItem.path("name").asText(null));
                    result.put("singer", getSingerName(songItem));
                    result.put("interval", songItem.path("interval").asInt(0));

                    JsonNode album = songItem.path("album");
                    if (album.isObject()) {
                        result.put("albumName", album.path("name").asText(null));
                        result.put("albumMid", album.path("mid").asText(null));
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("QQ Music search failed: {}", e.getMessage());
        }
        return null;
    }

    private String getSingerName(JsonNode song) {
        JsonNode singers = song.path("singer");
        if (singers.isArray() && !singers.isEmpty()) {
            return singers.get(0).path("name").asText(null);
        }
        return null;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://y.qq.com");
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.set("Accept", "application/json");
        return headers;
    }
}
