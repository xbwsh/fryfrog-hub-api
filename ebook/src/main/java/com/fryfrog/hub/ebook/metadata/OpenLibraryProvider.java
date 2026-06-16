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
public class OpenLibraryProvider implements BookMetadataProvider {

    private static final String SEARCH_URL = "https://openlibrary.org/search.json";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenLibraryProvider(ObjectMapper objectMapper) {
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
        return "OpenLibrary";
    }

    @Override
    public List<BookSearchResult> search(String title, String author) {
        String keyword = String.format("%s %s", title, author).trim();
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        log.info("OpenLibrary search query: '{}'", keyword);

        try {
            String url = SEARCH_URL + "?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&limit=10&fields=key,title,author_name,publisher,isbn,publish_year,subject,first_publish_year,number_of_pages_median,language";
            String json = httpGet(url);

            JsonNode root = objectMapper.readTree(json);
            JsonNode docs = root.path("docs");
            if (docs == null || !docs.isArray()) {
                return Collections.emptyList();
            }

            List<BookSearchResult> results = new ArrayList<>();
            for (JsonNode doc : docs) {
                BookSearchResult r = new BookSearchResult();
                r.setId(doc.path("key").asText("").replace("/books/", ""));
                r.setTitle(doc.path("title").asText(""));
                r.setPlatform("openlibrary");

                JsonNode authors = doc.path("author_name");
                if (authors != null && authors.isArray() && !authors.isEmpty()) {
                    r.setAuthor(authors.get(0).asText(""));
                }

                JsonNode publishers = doc.path("publisher");
                if (publishers != null && publishers.isArray() && !publishers.isEmpty()) {
                    r.setPublisher(publishers.get(0).asText(""));
                }

                JsonNode isbns = doc.path("isbn");
                if (isbns != null && isbns.isArray()) {
                    for (JsonNode isbn : isbns) {
                        String isbnStr = isbn.asText("");
                        if (isbnStr.length() == 13) {
                            r.setIsbn(isbnStr);
                            break;
                        }
                    }
                }

                r.setYear(doc.path("first_publish_year").asInt(0));
                r.setPageCount(doc.path("number_of_pages_median").asInt(0));

                JsonNode subjects = doc.path("subject");
                if (subjects != null && subjects.isArray() && !subjects.isEmpty()) {
                    r.setGenre(subjects.get(0).asText(""));
                }

                String coverId = doc.path("cover_i").asText("");
                if (!coverId.isEmpty()) {
                    r.setCoverUrl("https://covers.openlibrary.org/b/id/" + coverId + "-L.jpg");
                }

                results.add(r);
            }
            log.info("OpenLibrary found {} results for: {}", results.size(), title);
            return results;
        } catch (Exception e) {
            log.error("OpenLibrary search failed for '{}' - '{}': {}", title, author, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BookSearchResult getDetail(String id) {
        try {
            String url = "https://openlibrary.org/books/" + id + ".json";
            String json = httpGet(url);

            JsonNode doc = objectMapper.readTree(json);
            BookSearchResult r = new BookSearchResult();
            r.setId(id);
            r.setTitle(doc.path("title").asText(""));
            r.setPlatform("openlibrary");

            JsonNode authors = doc.path("authors");
            if (authors != null && authors.isArray() && !authors.isEmpty()) {
                JsonNode authorRef = authors.get(0).path("author");
                if (authorRef != null) {
                    String authorKey = authorRef.path("key").asText("");
                    if (!authorKey.isEmpty()) {
                        String authorJson = httpGet("https://openlibrary.org" + authorKey + ".json");
                        JsonNode authorDoc = objectMapper.readTree(authorJson);
                        r.setAuthor(authorDoc.path("name").asText(""));
                    }
                }
            }

            JsonNode publishers = doc.path("publishers");
            if (publishers != null && publishers.isArray() && !publishers.isEmpty()) {
                r.setPublisher(publishers.get(0).asText(""));
            }

            r.setYear(doc.path("first_publish_year").asInt(0));
            r.setPageCount(doc.path("number_of_pages").asInt(0));

            String coverId = doc.path("covers").path(0).asText("");
            if (!coverId.isEmpty()) {
                r.setCoverUrl("https://covers.openlibrary.org/b/id/" + coverId + "-L.jpg");
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
            log.info("OpenLibrary fetching cover for: {}", result.getTitle());
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
            log.warn("Failed to get cover from OpenLibrary for {}: {}", result.getTitle(), e.getMessage());
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