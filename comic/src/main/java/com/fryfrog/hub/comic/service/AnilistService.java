package com.fryfrog.hub.comic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.comic.dto.anilist.AnilistSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AnilistService {

    private static final String GRAPHQL_URL = "https://graphql.anilist.co";

    private static final String SEARCH_MANGA_QUERY = """
            query ($search: String) {
              Page(perPage: 10) {
                media(search: $search, type: MANGA) {
                  id
                  title {
                    romaji
                    english
                    native
                  }
                  coverImage {
                    large
                    medium
                  }
                  description(asHtml: false)
                  meanScore
                  genres
                  startDate { year month day }
                  volumes
                  status
                  type
                  staff(perPage: 10) {
                    edges {
                      node { name { full } }
                      role
                    }
                  }
                }
              }
            }
            """;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${hub.anilist.language:zh-CN}")
    private String language;

    public AnilistService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return true;
    }

    public List<AnilistSearchResult.MediaItem> searchManga(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            Map<String, Object> variables = Map.of("search", query);
            Map<String, Object> body = Map.of("query", SEARCH_MANGA_QUERY, "variables", variables);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    GRAPHQL_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                AnilistSearchResult result = objectMapper.readValue(response.getBody(), AnilistSearchResult.class);
                if (result.getData() != null && result.getData().getPage() != null) {
                    List<AnilistSearchResult.MediaItem> items = result.getData().getPage().getMedia();
                    if (items != null) {
                        log.info("AniList search for '{}' returned {} results", query, items.size());
                        return items;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to search manga on AniList: {}", e.getMessage(), e);
        }
        return List.of();
    }

    public AnilistSearchResult.MediaItem getMangaDetail(Integer mangaId) {
        if (mangaId == null) return null;

        String detailQuery = """
                query ($id: Int) {
                  Media(id: $id, type: MANGA) {
                    id
                    title { romaji english native }
                    coverImage { large medium }
                    description(asHtml: false)
                    meanScore
                    genres
                    startDate { year month day }
                    volumes
                    status
                    type
                    staff(perPage: 10) {
                      edges {
                        node { name { full } }
                        role
                      }
                    }
                  }
                }
                """;

        try {
            Map<String, Object> variables = Map.of("id", mangaId);
            Map<String, Object> body = Map.of("query", detailQuery, "variables", variables);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    GRAPHQL_URL, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var jsonNode = objectMapper.readTree(response.getBody());
                var mediaNode = jsonNode.path("data").path("Media");
                if (!mediaNode.isMissingNode()) {
                    return objectMapper.treeToValue(mediaNode, AnilistSearchResult.MediaItem.class);
                }
            }
        } catch (Exception e) {
            log.error("Failed to get manga detail from AniList: id={}: {}", mangaId, e.getMessage(), e);
        }
        return null;
    }
}
