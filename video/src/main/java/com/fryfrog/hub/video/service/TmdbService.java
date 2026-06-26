package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.video.dto.TmdbEpisodeDetail;
import com.fryfrog.hub.video.dto.TmdbMovieDetail;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TmdbService {

    private static final String BASE_URL = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private final RestTemplate restTemplate;
    private final SystemSettingService settingService;
    private final ConcurrentHashMap<String, CacheEntry<TmdbSearchResult>> searchCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CacheEntry<TmdbMovieDetail>> movieDetailCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CacheEntry<TmdbTvDetail>> tvDetailCache = new ConcurrentHashMap<>();

    public TmdbService(RestTemplate restTemplate, SystemSettingService settingService) {
        this.restTemplate = restTemplate;
        this.settingService = settingService;
    }

    private static class CacheEntry<T> {
        final T value;
        final long timestamp;
        CacheEntry(T value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private String getApiKey() {
        return settingService.getValue("hub.tmdb.api-key", "");
    }

    private String getLanguage() {
        return settingService.getValue("hub.tmdb.language", "zh-CN");
    }

    private String getImageSize() {
        return settingService.getValue("hub.tmdb.image-size", "original");
    }

    private boolean isIncludeAdult() {
        return settingService.getBoolean("hub.tmdb.include-adult", true);
    }

    public boolean isConfigured() {
        String apiKey = getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + getApiKey());
        return headers;
    }

    private <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType) {
        HttpEntity<Void> entity = new HttpEntity<>(createAuthHeaders());
        return restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
    }

    public List<TmdbSearchResult.TmdbSearchItem> searchMovies(String query) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cacheKey = "movie:" + query;
        CacheEntry<TmdbSearchResult> cached = searchCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value.getResults() != null ? cached.value.getResults() : List.of();
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/search/movie")
                .queryParam("language", getLanguage())
                .queryParam("query", query)
                .queryParam("include_adult", String.valueOf(isIncludeAdult()))
                .toUriString();

        try {
            ResponseEntity<TmdbSearchResult> response = getForEntity(url, TmdbSearchResult.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbSearchResult result = response.getBody();
                searchCache.put(cacheKey, new CacheEntry<>(result));
                List<TmdbSearchResult.TmdbSearchItem> results = result.getResults();
                if (results != null) {
                    results.forEach(item -> item.setMediaType("movie"));
                }
                return results != null ? results : List.of();
            }
        } catch (Exception e) {
            log.error("Failed to search movies on TMDB: {}", e.getMessage(), e);
        }
        return List.of();
    }

    public List<TmdbSearchResult.TmdbSearchItem> searchTv(String query) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cacheKey = "tv:" + query;
        CacheEntry<TmdbSearchResult> cached = searchCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value.getResults() != null ? cached.value.getResults() : List.of();
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/search/tv")
                .queryParam("language", getLanguage())
                .queryParam("query", query)
                .queryParam("include_adult", String.valueOf(isIncludeAdult()))
                .toUriString();

        try {
            ResponseEntity<TmdbSearchResult> response = getForEntity(url, TmdbSearchResult.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbSearchResult result = response.getBody();
                searchCache.put(cacheKey, new CacheEntry<>(result));
                List<TmdbSearchResult.TmdbSearchItem> results = result.getResults();
                if (results != null) {
                    results.forEach(item -> item.setMediaType("tv"));
                }
                return results != null ? results : List.of();
            }
        } catch (Exception e) {
            log.error("Failed to search TV shows on TMDB: {}", e.getMessage(), e);
        }
        return List.of();
    }

    public List<TmdbSearchResult.TmdbSearchItem> searchMulti(String query) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/search/multi")
                .queryParam("language", getLanguage())
                .queryParam("query", query)
                .queryParam("include_adult", String.valueOf(isIncludeAdult()))
                .toUriString();

        try {
            ResponseEntity<TmdbSearchResult> response = getForEntity(url, TmdbSearchResult.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<TmdbSearchResult.TmdbSearchItem> results = response.getBody().getResults();
                return results != null ? results : List.of();
            }
        } catch (Exception e) {
            log.error("Failed to search multi on TMDB: {}", e.getMessage(), e);
        }
        return List.of();
    }

    public TmdbMovieDetail getMovieDetail(Long movieId) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        CacheEntry<TmdbMovieDetail> cached = movieDetailCache.get(movieId);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/movie/" + movieId)
                .queryParam("language", getLanguage())
                .queryParam("append_to_response", "credits")
                .toUriString();

        try {
            ResponseEntity<TmdbMovieDetail> response = getForEntity(url, TmdbMovieDetail.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbMovieDetail detail = response.getBody();
                movieDetailCache.put(movieId, new CacheEntry<>(detail));
                return detail;
            }
        } catch (Exception e) {
            log.error("Failed to get movie detail from TMDB: {}", e.getMessage(), e);
        }
        return null;
    }

    public TmdbTvDetail getTvDetail(Long tvId) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        CacheEntry<TmdbTvDetail> cached = tvDetailCache.get(tvId);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/tv/" + tvId)
                .queryParam("language", getLanguage())
                .queryParam("append_to_response", "created_by,credits")
                .toUriString();

        try {
            ResponseEntity<TmdbTvDetail> response = getForEntity(url, TmdbTvDetail.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbTvDetail detail = response.getBody();
                tvDetailCache.put(tvId, new CacheEntry<>(detail));
                return detail;
            }
        } catch (Exception e) {
            log.error("Failed to get TV detail from TMDB: {}", e.getMessage(), e);
        }
        return null;
    }

    public TmdbEpisodeDetail getTvEpisodeDetail(Long tvId, Integer seasonNumber, Integer episodeNumber) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/tv/" + tvId + "/season/" + seasonNumber + "/episode/" + episodeNumber)
                .queryParam("language", getLanguage())
                .toUriString();

        try {
            ResponseEntity<TmdbEpisodeDetail> response = getForEntity(url, TmdbEpisodeDetail.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.error("Failed to get TV episode detail from TMDB: tvId={}, season={}, episode={}: {}", tvId, seasonNumber, episodeNumber, e.getMessage(), e);
        }
        return null;
    }

    public String getPosterUrl(String posterPath) {
        if (posterPath == null) return null;
        return IMAGE_BASE_URL + "/" + getImageSize() + posterPath;
    }

    public String getBackdropUrl(String backdropPath) {
        if (backdropPath == null) return null;
        return IMAGE_BASE_URL + "/" + getImageSize() + backdropPath;
    }
}
