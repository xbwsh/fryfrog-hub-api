package com.fryfrog.hub.music.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.music.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@Slf4j
public class NeteaseCloudMusicProvider implements MusicMetadataProvider {

    private static final String SEARCH_URL = "https://music.163.com/api/search/get/web";
    private static final String LYRIC_URL = "https://music.163.com/api/song/lyric";
    private static final String SONG_DETAIL_URL = "https://music.163.com/api/song/detail";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NeteaseCloudMusicProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getName() {
        return "NeteaseCloudMusic";
    }

    @Override
    public List<SearchResult> search(String artist, String title, String album) {
        String keyword = String.format("%s %s", artist, title).trim();
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        log.info("NeteaseCloudMusic search query: '{}'", keyword);

        try {
            String url = SEARCH_URL + "?s=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&type=1&limit=10";
            String json = httpGet(url);

            JsonNode root = objectMapper.readTree(json);
            if (root.path("code").asInt(0) != 200) {
                return Collections.emptyList();
            }

            JsonNode songs = root.path("result").path("songs");
            if (songs == null || !songs.isArray()) {
                return Collections.emptyList();
            }

            List<SearchResult> results = new ArrayList<>();
            for (JsonNode song : songs) {
                SearchResult r = new SearchResult();
                r.setId(String.valueOf(song.path("id").asLong()));
                r.setName(song.path("name").asText(""));
                r.setPlatform("netease");

                JsonNode artists = song.path("artists");
                if (artists != null && artists.isArray() && !artists.isEmpty()) {
                    r.setArtist(artists.get(0).path("name").asText(""));
                }

                JsonNode songAlbum = song.path("album");
                if (songAlbum != null) {
                    r.setAlbum(songAlbum.path("name").asText(""));
                    String picUrl = songAlbum.path("picUrl").asText("");
                    if (!picUrl.isEmpty()) {
                        r.setPicUrl(picUrl);
                    }
                }

                results.add(r);
            }
            log.info("NeteaseCloudMusic found {} results for: {} - {}", results.size(), artist, title);
            return results;
        } catch (Exception e) {
            log.error("NeteaseCloudMusic search failed for '{}' - '{}': {}", artist, title, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String getLyrics(SearchResult result) {
        if (result.getId() == null || result.getId().isBlank()) {
            return null;
        }

        try {
            String url = LYRIC_URL + "?id=" + result.getId() + "&lv=1";
            String json = httpGet(url);

            JsonNode root = objectMapper.readTree(json);
            String lyrics = root.path("lrc").path("lyric").asText("");
            if (lyrics.isEmpty()) {
                return null;
            }

            log.info("NeteaseCloudMusic got lyrics for: {}", result.getName());
            return lyrics;
        } catch (Exception e) {
            log.warn("Failed to get lyrics from NeteaseCloudMusic for {}: {}", result.getName(), e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] getCover(SearchResult result) {
        String picUrl = result.getPicUrl();
        if (picUrl == null || picUrl.isBlank()) {
            if (result.getId() != null && !result.getId().isBlank()) {
                picUrl = fetchCoverUrlFromDetail(result.getId());
            }
        }

        if (picUrl == null || picUrl.isBlank()) {
            return null;
        }

        try {
            log.info("NeteaseCloudMusic fetching cover for: {}", result.getName());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(picUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.warn("Failed to get cover from NeteaseCloudMusic for {}: {}", result.getName(), e.getMessage());
        }
        return null;
    }

    @Override
    public SearchResult findBestMatch(List<SearchResult> results, String artist, String title) {
        if (results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    private String fetchCoverUrlFromDetail(String songId) {
        try {
            String url = SONG_DETAIL_URL + "?id=" + songId + "&ids=[" + songId + "]";
            String json = httpGet(url);

            JsonNode root = objectMapper.readTree(json);
            JsonNode songs = root.path("songs");
            if (songs != null && songs.isArray() && !songs.isEmpty()) {
                String picUrl = songs.get(0).path("album").path("picUrl").asText("");
                if (!picUrl.isEmpty()) {
                    return picUrl;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch song detail for {}: {}", songId, e.getMessage());
        }
        return null;
    }

    private String httpGet(String url) throws Exception {
        java.net.URI uri = new java.net.URI(url);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com")
                .header("Accept", "application/json")
                .GET()
                .build();
        java.net.http.HttpResponse<byte[]> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body();
        return new String(body, StandardCharsets.UTF_8);
    }
}
