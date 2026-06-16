package com.fryfrog.hub.comic.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.comic.dto.ComicSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@Slf4j
public class AniListProvider implements ComicMetadataProvider {

    private static final String GRAPHQL_URL = "https://graphql.anilist.co";

    private static final String SEARCH_QUERY = """
            query ($search: String, $type: MediaType) {
              Page(page: 1, perPage: 10) {
                media(search: $search, type: $type, format_in: [MANGA, ONE_SHOT, COMIC]) {
                  id
                  title {
                    romaji
                    native
                    english
                  }
                  authors: staff(sort: ROLE, perPage: 1) {
                    nodes {
                      name {
                        full
                      }
                    }
                  }
                  coverImage {
                    large
                    medium
                  }
                  genres
                  description(asHtml: false)
                  startDate {
                    year
                  }
                  chapters
                  volumes
                  averageScore
                }
              }
            }
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AniListProvider(ObjectMapper objectMapper) {
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
        return "AniList";
    }

    @Override
    public List<ComicSearchResult> search(String title, String author) {
        String keyword = title != null ? title.trim() : "";
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        log.info("AniList search query: '{}'", keyword);

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "query", SEARCH_QUERY,
                    "variables", Map.of("search", keyword, "type", "MANGA")
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GRAPHQL_URL))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AniList returned status {}", response.statusCode());
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode mediaList = root.path("data").path("Page").path("media");
            if (mediaList == null || !mediaList.isArray()) {
                return Collections.emptyList();
            }

            List<ComicSearchResult> results = new ArrayList<>();
            for (JsonNode media : mediaList) {
                ComicSearchResult r = new ComicSearchResult();
                r.setId(String.valueOf(media.path("id").asInt()));
                r.setPlatform("anilist");

                JsonNode titleNode = media.path("title");
                String romaji = titleNode.path("romaji").asText("");
                String english = titleNode.path("english").asText("");
                String native_ = titleNode.path("native").asText("");
                r.setTitle(!english.isEmpty() ? english : (!romaji.isEmpty() ? romaji : native_));
                r.setSeries(romaji);

                JsonNode authors = media.path("authors").path("nodes");
                if (authors.isArray() && !authors.isEmpty()) {
                    r.setAuthor(authors.get(0).path("name").path("full").asText(""));
                }

                JsonNode cover = media.path("coverImage");
                String coverUrl = cover.path("large").asText("");
                if (coverUrl.isEmpty()) {
                    coverUrl = cover.path("medium").asText("");
                }
                r.setCoverUrl(coverUrl);

                JsonNode genres = media.path("genres");
                if (genres.isArray() && !genres.isEmpty()) {
                    List<String> genreList = new ArrayList<>();
                    genres.forEach(g -> genreList.add(g.asText()));
                    r.setGenre(String.join(", ", genreList));
                }

                String desc = media.path("description").asText("");
                if (desc != null) {
                    desc = desc.replaceAll("<[^>]+>", "").trim();
                }
                r.setDescription(desc);

                JsonNode startDate = media.path("startDate");
                if (startDate.has("year") && !startDate.path("year").isNull()) {
                    r.setYear(startDate.path("year").asInt());
                }

                if (media.has("averageScore") && !media.path("averageScore").isNull()) {
                    r.setRating(media.path("averageScore").asDouble() / 10.0);
                }

                results.add(r);
            }
            log.info("AniList found {} results for: {}", results.size(), keyword);
            return results;
        } catch (Exception e) {
            log.error("AniList search failed for '{}': {}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public byte[] getCover(ComicSearchResult result) {
        String coverUrl = result.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }

        try {
            log.info("AniList fetching cover for: {}", result.getTitle());
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
            log.warn("Failed to get cover from AniList for {}: {}", result.getTitle(), e.getMessage());
        }
        return null;
    }

    @Override
    public ComicSearchResult findBestMatch(List<ComicSearchResult> results, String title, String author) {
        if (results.isEmpty()) {
            return null;
        }

        String lowerTitle = title != null ? title.toLowerCase() : "";
        String lowerAuthor = author != null ? author.toLowerCase() : "";

        return results.stream()
                .min(Comparator.<ComicSearchResult, Integer>comparing(r -> {
                    int score = 0;
                    String rTitle = r.getTitle() != null ? r.getTitle().toLowerCase() : "";
                    String rSeries = r.getSeries() != null ? r.getSeries().toLowerCase() : "";
                    if (rTitle.contains(lowerTitle) || lowerTitle.contains(rTitle)) score -= 10;
                    if (rSeries.contains(lowerTitle) || lowerTitle.contains(rSeries)) score -= 5;
                    String rAuthor = r.getAuthor() != null ? r.getAuthor().toLowerCase() : "";
                    if (!lowerAuthor.isEmpty() && rAuthor.contains(lowerAuthor)) score -= 3;
                    return score;
                }))
                .orElse(results.get(0));
    }
}
