package com.fryfrog.hub.ebook.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.ebook.dto.BookSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@Slf4j
public class HardcoverProvider implements BookMetadataProvider {

    private static final String GRAPHQL_URL = "https://hardcover.app/api/graphql";

    private static final String SEARCH_QUERY = """
            query ($query: String!) {
              books(query: $query, perPage: 10) {
                id
                title
                slug
                authors { name }
                publisher
                releaseDate
                pageCount
                description
                image { url }
                tags { name }
                ratingsCount
                avgRating
              }
            }
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HardcoverProvider(ObjectMapper objectMapper,
                             @Value("${hub.proxy.host:}") String proxyHost,
                             @Value("${hub.proxy.port:0}") int proxyPort) {
        this.objectMapper = objectMapper;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.httpClient = builder.build();
    }

    @Override
    public String getName() {
        return "Hardcover";
    }

    @Override
    public List<BookSearchResult> search(String title, String author) {
        String keyword = String.format("%s %s", title, author).trim();
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        log.info("Hardcover search query: '{}'", keyword);

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "query", SEARCH_QUERY,
                    "variables", Map.of("query", keyword)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GRAPHQL_URL))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Hardcover returned status {}", response.statusCode());
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode books = root.path("data").path("books");
            if (books == null || !books.isArray()) {
                return Collections.emptyList();
            }

            List<BookSearchResult> results = new ArrayList<>();
            for (JsonNode book : books) {
                BookSearchResult r = new BookSearchResult();
                r.setId(book.path("id").asText(""));
                r.setTitle(book.path("title").asText(""));
                r.setPlatform("hardcover");

                JsonNode authors = book.path("authors");
                if (authors.isArray() && !authors.isEmpty()) {
                    r.setAuthor(authors.get(0).path("name").asText(""));
                }

                r.setPublisher(book.path("publisher").asText(""));
                String releaseDate = book.path("releaseDate").asText("");
                if (releaseDate != null && releaseDate.length() >= 4) {
                    try {
                        r.setYear(Integer.parseInt(releaseDate.substring(0, 4)));
                    } catch (NumberFormatException ignored) {}
                }

                r.setDescription(book.path("description").asText(""));
                r.setPageCount(book.path("pageCount").asInt(0));

                JsonNode image = book.path("image");
                if (image != null) {
                    r.setCoverUrl(image.path("url").asText(""));
                }

                JsonNode tags = book.path("tags");
                if (tags.isArray() && !tags.isEmpty()) {
                    List<String> genreList = new ArrayList<>();
                    tags.forEach(t -> genreList.add(t.path("name").asText("")));
                    r.setGenre(String.join(", ", genreList));
                }

                if (book.has("avgRating") && !book.path("avgRating").isNull()) {
                    r.setRating(book.path("avgRating").asDouble());
                }

                results.add(r);
            }
            log.info("Hardcover found {} results for: {}", results.size(), keyword);
            return results;
        } catch (Exception e) {
            log.error("Hardcover search failed for '{}': {}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BookSearchResult getDetail(String id) {
        return null;
    }

    @Override
    public byte[] getCover(BookSearchResult result) {
        String coverUrl = result.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }

        try {
            log.info("Hardcover fetching cover for: {}", result.getTitle());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(coverUrl))
                    .header("User-Agent", "fryfrog-hub/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.warn("Failed to get cover from Hardcover for {}: {}", result.getTitle(), e.getMessage());
        }
        return null;
    }

    @Override
    public BookSearchResult findBestMatch(List<BookSearchResult> results, String title, String author) {
        if (results.isEmpty()) {
            return null;
        }

        String lowerTitle = title != null ? title.toLowerCase() : "";
        String lowerAuthor = author != null ? author.toLowerCase() : "";

        return results.stream()
                .min(Comparator.<BookSearchResult, Integer>comparing(r -> {
                    int score = 0;
                    String rTitle = r.getTitle() != null ? r.getTitle().toLowerCase() : "";
                    if (rTitle.contains(lowerTitle) || lowerTitle.contains(rTitle)) score -= 10;
                    String rAuthor = r.getAuthor() != null ? r.getAuthor().toLowerCase() : "";
                    if (!lowerAuthor.isEmpty() && rAuthor.contains(lowerAuthor)) score -= 5;
                    return score;
                }))
                .orElse(results.get(0));
    }
}
