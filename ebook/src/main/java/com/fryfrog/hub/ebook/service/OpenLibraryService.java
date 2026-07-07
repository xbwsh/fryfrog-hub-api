package com.fryfrog.hub.ebook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class OpenLibraryService {

    private static final String BASE_URL = "https://openlibrary.org";
    private static final String COVERS_BASE = "https://covers.openlibrary.org/b";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenLibraryService(@Qualifier("scraperRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public List<SearchResult> searchBooks(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();

        try {
            String url = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(BASE_URL + "/search.json")
                    .queryParam("q", keyword)
                    .queryParam("limit", 5)
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/0.1.0");
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Open Library returned status {} for search '{}'", response.getStatusCode(), keyword);
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode docs = root.path("docs");
            if (!docs.isArray()) return List.of();

            List<SearchResult> results = new ArrayList<>();
            for (JsonNode doc : docs) {
                results.add(parseSearchResult(doc));
            }

            log.info("Open Library search for '{}' returned {} results", keyword, results.size());
            return results;
        } catch (Exception e) {
            log.error("Failed to search Open Library: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private SearchResult parseSearchResult(JsonNode doc) {
        SearchResult r = new SearchResult();
        r.setTitle(doc.path("title").asText(null));

        JsonNode authorName = doc.path("author_name");
        if (authorName.isArray() && authorName.size() > 0) {
            r.setAuthorName(authorName.get(0).asText(null));
        }

        if (doc.has("first_publish_year")) {
            r.setFirstPublishYear(doc.path("first_publish_year").asInt());
        }

        // Extract OLID from key like "/works/OL26910269W"
        String key = doc.path("key").asText(null);
        if (key != null) {
            int lastSlash = key.lastIndexOf('/');
            if (lastSlash >= 0) {
                r.setOlid(key.substring(lastSlash + 1));
            }
        }

        r.setCoverId(doc.has("cover_i") ? doc.path("cover_i").asLong() : null);

        List<String> isbns = new ArrayList<>();
        JsonNode isbnNode = doc.path("isbn");
        if (isbnNode.isArray()) {
            for (JsonNode isbn : isbnNode) {
                isbns.add(isbn.asText());
            }
        }
        r.setIsbns(isbns);

        List<String> publishers = new ArrayList<>();
        JsonNode pubNode = doc.path("publisher");
        if (pubNode.isArray()) {
            for (JsonNode p : pubNode) {
                publishers.add(p.asText());
            }
        }
        r.setPublishers(publishers);

        JsonNode langNode = doc.path("language");
        if (langNode.isArray() && langNode.size() > 0) {
            r.setLanguage(langNode.get(0).asText(null));
        }

        return r;
    }

    public String getCoverUrlByOlid(String olid) {
        if (olid == null || olid.isBlank()) return null;
        return COVERS_BASE + "/olid/" + olid + "-L.jpg";
    }

    public String getCoverUrlByIsbn(String isbn) {
        if (isbn == null || isbn.isBlank()) return null;
        return COVERS_BASE + "/isbn/" + isbn + "-L.jpg";
    }

    @Data
    public static class SearchResult {
        private String title;
        private String authorName;
        private Integer firstPublishYear;
        private String olid;
        private Long coverId;
        private List<String> isbns;
        private List<String> publishers;
        private String language;
    }
}
