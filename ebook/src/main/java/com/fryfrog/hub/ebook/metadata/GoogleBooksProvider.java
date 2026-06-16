package com.fryfrog.hub.ebook.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.ebook.dto.BookSearchResult;
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
public class GoogleBooksProvider implements BookMetadataProvider {

    private static final String SEARCH_URL = "https://www.googleapis.com/books/v1/volumes";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleBooksProvider(ObjectMapper objectMapper) {
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
        return "GoogleBooks";
    }

    @Override
    public List<BookSearchResult> search(String title, String author) {
        String keyword = String.format("%s %s", title, author).trim();
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        log.info("GoogleBooks search query: '{}'", keyword);

        try {
            String url = SEARCH_URL + "?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&maxResults=10&langRestrict=zh";
            String json = httpGet(url);

            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.path("items");
            if (items == null || !items.isArray()) {
                return Collections.emptyList();
            }

            List<BookSearchResult> results = new ArrayList<>();
            for (JsonNode item : items) {
                JsonNode volumeInfo = item.path("volumeInfo");
                if (volumeInfo == null) {
                    continue;
                }

                BookSearchResult r = new BookSearchResult();
                r.setId(item.path("id").asText(""));
                r.setTitle(volumeInfo.path("title").asText(""));
                r.setPlatform("google");

                JsonNode authors = volumeInfo.path("authors");
                if (authors != null && authors.isArray() && !authors.isEmpty()) {
                    r.setAuthor(authors.get(0).asText(""));
                }

                r.setPublisher(volumeInfo.path("publisher").asText(""));
                r.setYear(parseYear(volumeInfo.path("publishedDate").asText("")));
                r.setDescription(volumeInfo.path("description").asText(""));
                r.setPageCount(volumeInfo.path("pageCount").asInt(0));
                r.setLanguage(volumeInfo.path("language").asText(""));

                JsonNode categories = volumeInfo.path("categories");
                if (categories != null && categories.isArray() && !categories.isEmpty()) {
                    r.setGenre(categories.get(0).asText(""));
                }

                JsonNode imageLinks = volumeInfo.path("imageLinks");
                if (imageLinks != null) {
                    String thumbnail = imageLinks.path("thumbnail").asText("");
                    if (!thumbnail.isEmpty()) {
                        r.setCoverUrl(thumbnail);
                    }
                }

                JsonNode industryIdentifiers = volumeInfo.path("industryIdentifiers");
                if (industryIdentifiers != null && industryIdentifiers.isArray()) {
                    for (JsonNode identifier : industryIdentifiers) {
                        String type = identifier.path("type").asText("");
                        if ("ISBN_13".equals(type)) {
                            r.setIsbn(identifier.path("identifier").asText(""));
                            break;
                        }
                    }
                }

                results.add(r);
            }
            log.info("GoogleBooks found {} results for: {}", results.size(), title);
            return results;
        } catch (Exception e) {
            log.error("GoogleBooks search failed for '{}' - '{}': {}", title, author, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BookSearchResult getDetail(String id) {
        try {
            String url = SEARCH_URL + "/" + id;
            String json = httpGet(url);

            JsonNode item = objectMapper.readTree(json);
            JsonNode volumeInfo = item.path("volumeInfo");
            if (volumeInfo == null) {
                return null;
            }

            BookSearchResult r = new BookSearchResult();
            r.setId(item.path("id").asText(""));
            r.setTitle(volumeInfo.path("title").asText(""));
            r.setPlatform("google");

            JsonNode authors = volumeInfo.path("authors");
            if (authors != null && authors.isArray() && !authors.isEmpty()) {
                r.setAuthor(authors.get(0).asText(""));
            }

            r.setPublisher(volumeInfo.path("publisher").asText(""));
            r.setYear(parseYear(volumeInfo.path("publishedDate").asText("")));
            r.setDescription(volumeInfo.path("description").asText(""));
            r.setPageCount(volumeInfo.path("pageCount").asInt(0));
            r.setLanguage(volumeInfo.path("language").asText(""));

            JsonNode categories = volumeInfo.path("categories");
            if (categories != null && categories.isArray() && !categories.isEmpty()) {
                r.setGenre(categories.get(0).asText(""));
            }

            JsonNode imageLinks = volumeInfo.path("imageLinks");
            if (imageLinks != null) {
                String thumbnail = imageLinks.path("thumbnail").asText("");
                if (!thumbnail.isEmpty()) {
                    r.setCoverUrl(thumbnail);
                }
            }

            JsonNode industryIdentifiers = volumeInfo.path("industryIdentifiers");
            if (industryIdentifiers != null && industryIdentifiers.isArray()) {
                for (JsonNode identifier : industryIdentifiers) {
                    String type = identifier.path("type").asText("");
                    if ("ISBN_13".equals(type)) {
                        r.setIsbn(identifier.path("identifier").asText(""));
                        break;
                    }
                }
            }

            return r;
        } catch (Exception e) {
            log.warn("Failed to get book detail for {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] getCover(BookSearchResult result) {
        String coverUrl = result.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }

        try {
            log.info("GoogleBooks fetching cover for: {}", result.getTitle());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(coverUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.warn("Failed to get cover from GoogleBooks for {}: {}", result.getTitle(), e.getMessage());
        }
        return null;
    }

    @Override
    public BookSearchResult findBestMatch(List<BookSearchResult> results, String title, String author) {
        if (results.isEmpty()) {
            return null;
        }

        return results.stream()
                .max(Comparator.comparingDouble(r -> calculateMatchScore(r, title, author)))
                .orElse(results.get(0));
    }

    private double calculateMatchScore(BookSearchResult result, String title, String author) {
        double score = 0;

        if (result.getTitle() != null && title != null) {
            if (result.getTitle().equalsIgnoreCase(title)) {
                score += 10;
            } else if (result.getTitle().contains(title) || title.contains(result.getTitle())) {
                score += 5;
            }
        }

        if (result.getAuthor() != null && author != null) {
            if (result.getAuthor().equalsIgnoreCase(author)) {
                score += 8;
            } else if (result.getAuthor().contains(author) || author.contains(result.getAuthor())) {
                score += 4;
            }
        }

        return score;
    }

    private Integer parseYear(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            String yearStr = dateStr.replaceAll("[^\\d]", "");
            if (yearStr.length() >= 4) {
                return Integer.parseInt(yearStr.substring(0, 4));
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String httpGet(String url) throws Exception {
        java.net.URI uri = new java.net.URI(url);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .GET()
                .build();
        java.net.http.HttpResponse<byte[]> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body();
        return new String(body, StandardCharsets.UTF_8);
    }
}