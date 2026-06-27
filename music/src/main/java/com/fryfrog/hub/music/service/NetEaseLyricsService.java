package com.fryfrog.hub.music.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
@Slf4j
public class NetEaseLyricsService {

    private static final String SEARCH_URL = "https://music.163.com/api/search/get";
    private static final String LYRICS_URL = "https://music.163.com/api/song/lyric";

    private final RestTemplate restTemplate;

    @Value("${hub.music.scrape.netease-api:http://music.163.com}")
    private String neteaseApi;

    public NetEaseLyricsService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String searchLyrics(String artist, String title) {
        try {
            String songId = searchSongId(artist, title);
            if (songId == null) {
                return null;
            }
            return fetchLyrics(songId);
        } catch (Exception e) {
            log.warn("Failed to search lyrics from NetEase for {} - {}: {}", artist, title, e.getMessage());
            return null;
        }
    }

    private String searchSongId(String artist, String title) {
        String keywords = artist + " " + title;

        String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                .queryParam("s", keywords)
                .queryParam("type", "1")
                .queryParam("limit", "5")
                .queryParam("offset", "0")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", neteaseApi);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map result = (Map) response.getBody().get("result");
                if (result != null) {
                    List<Map> songs = (List<Map>) result.get("songs");
                    if (songs != null && !songs.isEmpty()) {
                        return String.valueOf(songs.get(0).get("id"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NetEase search failed: {}", e.getMessage());
        }
        return null;
    }

    private String fetchLyrics(String songId) {
        String url = UriComponentsBuilder.fromHttpUrl(LYRICS_URL)
                .queryParam("id", songId)
                .queryParam("lv", "1")
                .queryParam("tv", "1")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", neteaseApi);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map lrc = (Map) response.getBody().get("lrc");
                if (lrc != null) {
                    String lyrics = (String) lrc.get("lyric");
                    if (lyrics != null && !lyrics.isBlank()) {
                        return lyrics;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NetEase lyrics fetch failed: {}", e.getMessage());
        }
        return null;
    }

    public String searchCoverUrl(String artist, String title) {
        try {
            String songId = searchSongId(artist, title);
            if (songId == null) {
                return null;
            }
            return fetchSongCover(songId);
        } catch (Exception e) {
            log.warn("Failed to search cover from NetEase: {}", e.getMessage());
            return null;
        }
    }

    public String searchArtistImage(String artist) {
        try {
            String url = "https://music.163.com/api/search/get?s=" + artist + "&type=100&limit=1&offset=0";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Referer", neteaseApi);
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map result = (Map) response.getBody().get("result");
                if (result != null) {
                    List<Map> artists = (List<Map>) result.get("artists");
                    if (artists != null && !artists.isEmpty()) {
                        return (String) artists.get(0).get("picUrl");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NetEase artist image search failed: {}", e.getMessage());
        }
        return null;
    }

    private String fetchSongCover(String songId) {
        String url = "https://music.163.com/api/song/detail?ids=" + songId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", neteaseApi);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map> songs = (List<Map>) response.getBody().get("songs");
                if (songs != null && !songs.isEmpty()) {
                    Map song = songs.get(0);
                    Map album = (Map) song.get("album");
                    if (album != null) {
                        return (String) album.get("picUrl");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NetEase cover fetch failed: {}", e.getMessage());
        }
        return null;
    }
}
