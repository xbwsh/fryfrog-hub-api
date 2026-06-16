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
public class DoubanBookProvider implements BookMetadataProvider {

    private static final String SEARCH_URL = "https://frodo.douban.com/api/v2/book/search";
    private static final String BOOK_URL = "https://frodo.douban.com/api/v2/book/";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DoubanBookProvider(ObjectMapper objectMapper) {
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
        return "DoubanBook";
    }

    @Override
    public List<BookSearchResult> search(String title, String author) {
        String keyword = String.format("%s %s", title, author).trim();
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        log.info("DoubanBook search query: '{}'", keyword);

        try {
            String url = SEARCH_URL + "?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&count=10&apiKey=0ac44ae016490db2204ce0a042db2916";
            String json = httpGet(url);

            JsonNode root = objectMapper.readTree(json);
            JsonNode books = root.path("books");
            if (books == null || !books.isArray()) {
                return Collections.emptyList();
            }

            List<BookSearchResult> results = new ArrayList<>();
            for (JsonNode book : books) {
                BookSearchResult r = new BookSearchResult();
                r.setId(book.path("id").asText(""));
                r.setTitle(book.path("title").asText(""));
                r.setPlatform("douban");

                JsonNode authors = book.path("author");
                if (authors != null && authors.isArray() && !authors.isEmpty()) {
                    r.setAuthor(authors.get(0).asText(""));
                }

                r.setPublisher(book.path("publisher").asText(""));
                r.setIsbn(book.path("isbn13").asText(""));
                r.setYear(parseYear(book.path("pubdate").asText("")));
                r.setGenre(book.path("category").asText(""));
                r.setDescription(book.path("summary").asText(""));
                r.setCoverUrl(book.path("image").asText(""));
                r.setPageCount(book.path("pages").asInt(0));

                JsonNode rating = book.path("rating");
                if (rating != null) {
                    r.setRating(rating.path("value").asDouble(0));
                }

                results.add(r);
            }
            log.info("DoubanBook found {} results for: {}", results.size(), title);
            return results;
        } catch (Exception e) {
            log.error("DoubanBook search failed for '{}' - '{}': {}", title, author, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BookSearchResult getDetail(String id) {
        try {
            String url = BOOK_URL + id + "?apiKey=0ac44ae016490db2204ce0a042db2916";
            String json = httpGet(url);

            JsonNode book = objectMapper.readTree(json);
            BookSearchResult r = new BookSearchResult();
            r.setId(book.path("id").asText(""));
            r.setTitle(book.path("title").asText(""));
            r.setPlatform("douban");

            JsonNode authors = book.path("author");
            if (authors != null && authors.isArray() && !authors.isEmpty()) {
                r.setAuthor(authors.get(0).asText(""));
            }

            r.setPublisher(book.path("publisher").asText(""));
            r.setIsbn(book.path("isbn13").asText(""));
            r.setYear(parseYear(book.path("pubdate").asText("")));
            r.setGenre(book.path("category").asText(""));
            r.setDescription(book.path("summary").asText(""));
            r.setCoverUrl(book.path("image").asText(""));
            r.setPageCount(book.path("pages").asInt(0));

            JsonNode rating = book.path("rating");
            if (rating != null) {
                r.setRating(rating.path("value").asDouble(0));
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
            log.info("DoubanBook fetching cover for: {}", result.getTitle());
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
            log.warn("Failed to get cover from DoubanBook for {}: {}", result.getTitle(), e.getMessage());
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

        if (result.getRating() != null) {
            score += result.getRating();
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
                .header("Referer", "https://book.douban.com")
                .header("Accept", "application/json")
                .GET()
                .build();
        java.net.http.HttpResponse<byte[]> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body();
        return new String(body, StandardCharsets.UTF_8);
    }
}