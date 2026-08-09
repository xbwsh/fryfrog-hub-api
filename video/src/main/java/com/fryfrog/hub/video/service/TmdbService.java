package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.dto.TmdbEpisodeDetail;
import com.fryfrog.hub.video.dto.TmdbMovieDetail;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.TmdbSeasonDetail;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.dto.TmdbTvImages;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TmdbService {

    private static final String BASE_URL = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p";

    private final RestTemplate restTemplate;

    @Value("${tmdb.api-key:}")
    private String apiKey;

    @Value("${tmdb.language:zh-CN}")
    private String language;

    /** 备用语言，默认日文 */
    private static final String FALLBACK_LANGUAGE = "ja-JP";

    @Value("${tmdb.image-size:original}")
    private String imageSize;

    @Value("${tmdb.include-adult:true}")
    private boolean includeAdult;

    private final Cache<String, TmdbSearchResult> searchCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Cache<Long, TmdbMovieDetail> movieDetailCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Cache<String, TmdbTvDetail> tvDetailCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Cache<String, TmdbTvImages> imagesCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).build();

    public TmdbService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private String getApiKey() {
        return apiKey;
    }

    /**
     * 当前配置的语言。对外可见，供 VideoScrapeService 判断是否需要语言回退。
     */
    public String getLanguage() {
        return language;
    }

    public String getFallbackLanguage() {
        return FALLBACK_LANGUAGE;
    }

    /**
     * 携带语言参数构建 URL，若 language 为 null 则不传该参数（让 TMDB 使用默认行为）。
     */
    private UriComponentsBuilder addLanguageParam(UriComponentsBuilder builder, String lang) {
        if (lang != null && !lang.isBlank()) {
            return builder.queryParam("language", lang);
        }
        return builder;
    }

    private String getImageSize() {
        return imageSize;
    }

    private boolean isIncludeAdult() {
        return includeAdult;
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
        return searchMovies(query, getLanguage());
    }

    /**
     * 搜索电影，可指定语言。language 为 null 时不传 language 参数（TMDB 默认行为）。
     */
    public List<TmdbSearchResult.TmdbSearchItem> searchMovies(String query, String language) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cacheKey = "movie:" + language + ":" + query;
        TmdbSearchResult cached = searchCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.getResults() != null ? cached.getResults() : List.of();
        }

        String url = addLanguageParam(
                UriComponentsBuilder.fromHttpUrl(BASE_URL + "/search/movie")
                        .queryParam("query", query)
                        .queryParam("include_adult", String.valueOf(isIncludeAdult())),
                language)
                .toUriString();

        try {
            ResponseEntity<TmdbSearchResult> response = getForEntity(url, TmdbSearchResult.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbSearchResult result = response.getBody();
                searchCache.put(cacheKey, result);
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
        return searchTv(query, getLanguage());
    }

    /**
     * 搜索电视剧，可指定语言。language 为 null 时不传 language 参数（TMDB 默认行为）。
     */
    public List<TmdbSearchResult.TmdbSearchItem> searchTv(String query, String language) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cacheKey = "tv:" + language + ":" + query;
        TmdbSearchResult cached = searchCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.getResults() != null ? cached.getResults() : List.of();
        }

        String url = addLanguageParam(
                UriComponentsBuilder.fromHttpUrl(BASE_URL + "/search/tv")
                        .queryParam("query", query)
                        .queryParam("include_adult", String.valueOf(isIncludeAdult())),
                language)
                .toUriString();

        try {
            ResponseEntity<TmdbSearchResult> response = getForEntity(url, TmdbSearchResult.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbSearchResult result = response.getBody();
                searchCache.put(cacheKey, result);
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

    public TmdbMovieDetail getMovieDetail(Long movieId) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        TmdbMovieDetail cached = movieDetailCache.getIfPresent(movieId);
        if (cached != null) {
            return cached;
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/movie/" + movieId)
                .queryParam("language", getLanguage())
                .queryParam("append_to_response", "credits")
                .toUriString();

        try {
            ResponseEntity<TmdbMovieDetail> response = getForEntity(url, TmdbMovieDetail.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbMovieDetail detail = response.getBody();
                movieDetailCache.put(movieId, detail);
                return detail;
            }
        } catch (Exception e) {
            log.error("Failed to get movie detail from TMDB: {}", e.getMessage(), e);
        }
        return null;
    }

    public TmdbTvDetail getTvDetail(Long tvId) {
        return getTvDetail(tvId, getLanguage());
    }

    /**
     * 获取电视剧详情，可指定语言。language 为 null 时不传 language 参数。
     */
    public TmdbTvDetail getTvDetail(Long tvId, String language) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cacheKey = tvId + ":" + language;
        TmdbTvDetail cached = tvDetailCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = addLanguageParam(
                UriComponentsBuilder.fromHttpUrl(BASE_URL + "/tv/" + tvId)
                        .queryParam("append_to_response", "created_by,credits"),
                language)
                .toUriString();

        try {
            ResponseEntity<TmdbTvDetail> response = getForEntity(url, TmdbTvDetail.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbTvDetail detail = response.getBody();
                tvDetailCache.put(cacheKey, detail);
                return detail;
            }
        } catch (Exception e) {
            log.error("Failed to get TV detail from TMDB: {}", e.getMessage(), e);
        }
        return null;
    }

    public TmdbSeasonDetail getTvSeasonDetail(Long tvId, Integer seasonNumber) {
        return getTvSeasonDetail(tvId, seasonNumber, getLanguage());
    }

    /**
     * 获取电视剧季详情，可指定语言。language 为 null 时不传 language 参数。
     */
    public TmdbSeasonDetail getTvSeasonDetail(Long tvId, Integer seasonNumber, String language) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String url = addLanguageParam(
                UriComponentsBuilder.fromHttpUrl(BASE_URL + "/tv/" + tvId + "/season/" + seasonNumber),
                language)
                .toUriString();

        try {
            ResponseEntity<TmdbSeasonDetail> response = getForEntity(url, TmdbSeasonDetail.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("TV season not found on TMDB: tvId={}, season={}", tvId, seasonNumber);
        } catch (Exception e) {
            log.warn("Failed to get TV season detail from TMDB: tvId={}, season={}: {}", tvId, seasonNumber, e.getMessage());
        }
        return null;
    }

    public TmdbEpisodeDetail getTvEpisodeDetail(Long tvId, Integer seasonNumber, Integer episodeNumber) {
        return getTvEpisodeDetail(tvId, seasonNumber, episodeNumber, getLanguage());
    }

    /**
     * 获取单集详情，可指定语言。language 为 null 时不传 language 参数。
     */
    public TmdbEpisodeDetail getTvEpisodeDetail(Long tvId, Integer seasonNumber, Integer episodeNumber, String language) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String url = addLanguageParam(
                UriComponentsBuilder.fromHttpUrl(BASE_URL + "/tv/" + tvId + "/season/" + seasonNumber + "/episode/" + episodeNumber),
                language)
                .toUriString();

        try {
            ResponseEntity<TmdbEpisodeDetail> response = getForEntity(url, TmdbEpisodeDetail.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("TV episode not found on TMDB: tvId={}, season={}, episode={}", tvId, seasonNumber, episodeNumber);
        } catch (Exception e) {
            log.warn("Failed to get TV episode detail from TMDB: tvId={}, season={}, episode={}: {}", tvId, seasonNumber, episodeNumber, e.getMessage());
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

    /**
     * 获取电视剧图片资源（含剧集字标 logos）。
     * 不传 language / include_image_language 参数，TMDB 默认返回所有语言的图片（含 null 无语言）。
     */
    public TmdbTvImages getTvImages(Long tvId) {
        return getImages("tv", tvId);
    }

    public TmdbTvImages getMovieImages(Long movieId) {
        return getImages("movie", movieId);
    }

    private TmdbTvImages getImages(String mediaType, Long id) {
        if (!isConfigured()) {
            throw new IllegalStateException("TMDB API key not configured");
        }

        String cacheKey = mediaType + ":" + id;
        TmdbTvImages cached = imagesCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/" + mediaType + "/" + id + "/images")
                .toUriString();

        try {
            ResponseEntity<TmdbTvImages> response = getForEntity(url, TmdbTvImages.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TmdbTvImages images = response.getBody();
                imagesCache.put(cacheKey, images);
                return images;
            }
        } catch (Exception e) {
            log.warn("Failed to get {} images from TMDB: {}: {}", mediaType, id, e.getMessage());
        }
        return null;
    }

    /**
     * 获取电视剧剧集字标 URL。语言优先级：配置语言 → 日文 → 英文 → 任意，同语言内按投票数取最高。
     * SVG 格式只能使用 original 尺寸，其他格式用配置尺寸。
     */
    public String getTvLogoUrl(Long tvId) {
        return getLogoUrl("tv", tvId);
    }

    public String getMovieLogoUrl(Long movieId) {
        return getLogoUrl("movie", movieId);
    }

    /**
     * 查询电视剧所有字标选项（按投票数降序），供前端选择设置。
     */
    public List<com.fryfrog.hub.video.dto.LogoOption> getTvLogoOptions(Long tvId) {
        return getLogoOptions("tv", tvId);
    }

    public List<com.fryfrog.hub.video.dto.LogoOption> getMovieLogoOptions(Long movieId) {
        return getLogoOptions("movie", movieId);
    }

    private List<com.fryfrog.hub.video.dto.LogoOption> getLogoOptions(String mediaType, Long id) {
        TmdbTvImages images = getImages(mediaType, id);
        if (images == null || images.getLogos() == null || images.getLogos().isEmpty()) {
            return List.of();
        }
        return images.getLogos().stream()
                .sorted(java.util.Comparator.comparingInt(
                        (com.fryfrog.hub.video.dto.TmdbTvImages.Logo l) ->
                                l.getVoteCount() != null ? l.getVoteCount() : 0)
                        .reversed())
                .map(l -> com.fryfrog.hub.video.dto.LogoOption.from(l, buildLogoUrl(l)))
                .toList();
    }

    /**
     * 根据 file_path 构建 logo 完整 URL（前端选定后按此下载）。
     */
    public String getLogoUrlByPath(String filePath) {
        if (filePath == null || filePath.isBlank()) return null;
        boolean isSvg = filePath.toLowerCase().endsWith(".svg");
        String size = isSvg ? "original" : getImageSize();
        return IMAGE_BASE_URL + "/" + size + filePath;
    }

    private String getLogoUrl(String mediaType, Long id) {
        TmdbTvImages images = getImages(mediaType, id);
        if (images == null || images.getLogos() == null || images.getLogos().isEmpty()) {
            return null;
        }

        List<String> languagePrefs = new java.util.ArrayList<>();
        String lang = getLanguage();
        if (lang != null && !lang.isBlank()) {
            languagePrefs.add(lang);
            // 兼容 "zh-CN" -> "zh" 这类前缀语言
            int dash = lang.indexOf('-');
            if (dash > 0) {
                languagePrefs.add(lang.substring(0, dash));
            }
        }
        languagePrefs.add("ja");
        languagePrefs.add("en");

        for (String pref : languagePrefs) {
            TmdbTvImages.Logo best = images.getLogos().stream()
                    .filter(l -> pref.equalsIgnoreCase(l.getIso6391()))
                    .max(java.util.Comparator.comparingInt(
                            l -> l.getVoteCount() != null ? l.getVoteCount() : 0))
                    .orElse(null);
            if (best != null) {
                return buildLogoUrl(best);
            }
        }

        // 任意语言兜底
        return images.getLogos().stream()
                .max(java.util.Comparator.comparingInt(l -> l.getVoteCount() != null ? l.getVoteCount() : 0))
                .map(this::buildLogoUrl)
                .orElse(null);
    }

    private String buildLogoUrl(TmdbTvImages.Logo logo) {
        if (logo.getFilePath() == null) return null;
        // TMDB 响应的 logo 对象没有 file_type 字段，用 file_path 扩展名判断 SVG
        boolean isSvg = logo.getFilePath().toLowerCase().endsWith(".svg");
        String size = isSvg ? "original" : getImageSize();
        return IMAGE_BASE_URL + "/" + size + logo.getFilePath();
    }
}
