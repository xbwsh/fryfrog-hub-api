package com.fryfrog.hub.music.service;

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

    private static final String SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
    private static final String LYRICS_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
    private static final String SONG_DETAIL_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg";
    private static final String COVER_BASE_URL = "https://y.gtimg.cn/music/photo_new/";

    private final RestTemplate restTemplate;

    public QQMusicService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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

        String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                .queryParam("w", keywords)
                .queryParam("p", "1")
                .queryParam("n", "5")
                .queryParam("format", "json")
                .queryParam("cr", "1")
                .toUriString();

        HttpHeaders headers = createHeaders();

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map data = (Map) response.getBody().get("data");
                if (data != null) {
                    Map songlist = (Map) data.get("song");
                    if (songlist != null) {
                        List<Map> list = (List<Map>) songlist.get("list");
                        if (list != null && !list.isEmpty()) {
                            return (String) list.get(0).get("songmid");
                        }
                    }
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
            log.warn("QQ Music lyrics fetch failed: {}", e.getMessage());
        }
        return null;
    }

    public String searchCoverUrl(String artist, String title) {
        try {
            String songMid = searchSongMid(artist, title);
            if (songMid == null) {
                return null;
            }
            return fetchAlbumCover(songMid);
        } catch (Exception e) {
            log.warn("Failed to search cover from QQ Music: {}", e.getMessage());
            return null;
        }
    }

    private String fetchAlbumCover(String songMid) {
        String url = UriComponentsBuilder.fromHttpUrl(SONG_DETAIL_URL)
                .queryParam("songmid", songMid)
                .queryParam("format", "json")
                .toUriString();

        HttpHeaders headers = createHeaders();

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map data = (Map) response.getBody().get("data");
                if (data != null) {
                    List<Map> trackInfo = (List<Map>) data.get("track_info");
                    if (trackInfo != null && !trackInfo.isEmpty()) {
                        Map album = (Map) trackInfo.get(0).get("album");
                        if (album != null) {
                            String albumMid = (String) album.get("mid");
                            if (albumMid != null && !albumMid.isBlank()) {
                                return COVER_BASE_URL + "T002R300x300M000" + albumMid + ".jpg";
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("QQ Music cover fetch failed: {}", e.getMessage());
        }
        return null;
    }

    public Map<String, String> searchSongInfo(String artist, String title) {
        String keywords = artist + " " + title;

        String url = UriComponentsBuilder.fromHttpUrl(SEARCH_URL)
                .queryParam("w", keywords)
                .queryParam("p", "1")
                .queryParam("n", "5")
                .queryParam("format", "json")
                .queryParam("cr", "1")
                .toUriString();

        HttpHeaders headers = createHeaders();

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map data = (Map) response.getBody().get("data");
                if (data != null) {
                    Map songlist = (Map) data.get("song");
                    if (songlist != null) {
                        List<Map> list = (List<Map>) songlist.get("list");
                        if (list != null && !list.isEmpty()) {
                            Map song = list.get(0);
                            Map<String, String> result = new HashMap<>();
                            result.put("songmid", (String) song.get("songmid"));
                            result.put("songname", (String) song.get("songname"));
                            result.put("singer", getSingerName(song));
                            result.put("albumname", (String) song.get("albumname"));
                            result.put("albummid", (String) song.get("albummid"));
                            result.put("interval", String.valueOf(song.get("interval")));
                            return result;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("QQ Music search failed: {}", e.getMessage());
        }
        return null;
    }

    private String getSingerName(Map song) {
        List<Map> singers = (List<Map>) song.get("singer");
        if (singers != null && !singers.isEmpty()) {
            return (String) singers.get(0).get("name");
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
