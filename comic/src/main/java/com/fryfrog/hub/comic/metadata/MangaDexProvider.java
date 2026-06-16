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
public class MangaDexProvider implements ComicMetadataProvider {

    private static final String SEARCH_URL = "https://api.mangadex.org/manga";
    private static final String COVER_URL = "https://api.mangadex.org/covers";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MangaDexProvider(ObjectMapper objectMapper) {
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
        return "MangaDex";
    }

    @Override
    public List<ComicSearchResult> search(String title, String author) {
        String keyword = title != null ? title.trim() : "";
        if (keyword.isBlank()) {
            return Collections.emptyList();
        }

        log.info("MangaDex search query: '{}'", keyword);

        try {
            String url = SEARCH_URL + "?title=" + java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&limit=10&includes[]=author&includes[]=artist&includes[]=cover_art&order[relevance]=desc";

            String json = httpGet(url);
            JsonNode root = objectMapper.readTree(json);

            JsonNode dataList = root.path("data");
            if (dataList == null || !dataList.isArray()) {
                return Collections.emptyList();
            }

            List<ComicSearchResult> results = new ArrayList<>();
            for (JsonNode manga : dataList) {
                ComicSearchResult r = new ComicSearchResult();
                r.setId(manga.path("id").asText());
                r.setPlatform("mangadex");

                JsonNode attributes = manga.path("attributes");

                JsonNode titleNode = attributes.path("title");
                String titleEn = titleNode.path("en").asText("");
                String titleJa = titleNode.path("ja-ro").asText("");
                String titleZh = getMultiLangField(titleNode, "zh");
                String titleZhCN = getMultiLangField(titleNode, "zh-hk");
                String bestTitle = !titleZh.isEmpty() ? titleZh :
                        (!titleZhCN.isEmpty() ? titleZhCN :
                                (!titleEn.isEmpty() ? titleEn : (!titleJa.isEmpty() ? titleJa : keyword)));
                r.setTitle(bestTitle);
                r.setSeries(bestTitle);

                JsonNode altTitles = attributes.path("altTitles");
                if (altTitles.isArray()) {
                    for (JsonNode alt : altTitles) {
                        String altZh = alt.path("zh").asText("");
                        String altZhHk = alt.path("zh-hk").asText("");
                        String altJaRo = alt.path("ja-ro").asText("");
                        if (!altZh.isEmpty()) {
                            r.setSeries(altZh);
                            break;
                        } else if (!altZhHk.isEmpty()) {
                            r.setSeries(altZhHk);
                            break;
                        } else if (!altJaRo.isEmpty() && r.getSeries().equals(bestTitle)) {
                            r.setSeries(altJaRo);
                        }
                    }
                }

                for (JsonNode rel : manga.path("relationships")) {
                    String relType = rel.path("type").asText();
                    if ("author".equals(relType) || "artist".equals(relType)) {
                        JsonNode nameNode = rel.path("attributes").path("name");
                        String authorName = nameNode.asText("");
                        if (authorName.isEmpty()) {
                            authorName = nameNode.path("en").asText("");
                        }
                        if (!authorName.isEmpty() && (r.getAuthor() == null || r.getAuthor().isBlank())) {
                            r.setAuthor(authorName);
                        }
                    }
                    if ("cover_art".equals(relType)) {
                        String fileName = rel.path("attributes").path("fileName").asText("");
                        if (!fileName.isEmpty()) {
                            r.setCoverUrl(COVER_URL + "/" + r.getId() + "/" + fileName + ".256.jpg");
                        }
                    }
                }

                JsonNode tags = attributes.path("tags");
                if (tags.isArray() && !tags.isEmpty()) {
                    List<String> genreList = new ArrayList<>();
                    for (JsonNode tag : tags) {
                        String tagName = tag.path("attributes").path("name").path("en").asText("");
                        if (!tagName.isEmpty()) {
                            genreList.add(tagName);
                        }
                    }
                    r.setGenre(String.join(", ", genreList));
                }

                String descEn = attributes.path("description").path("en").asText("");
                String descZh = attributes.path("description").path("zh").asText("");
                String desc = !descZh.isEmpty() ? descZh : descEn;
                if (desc != null) {
                    desc = desc.replaceAll("<[^>]+>", "").trim();
                    if (desc.length() > 500) {
                        desc = desc.substring(0, 500) + "...";
                    }
                }
                r.setDescription(desc);

                results.add(r);
            }
            log.info("MangaDex found {} results for: {}", results.size(), keyword);
            return results;
        } catch (Exception e) {
            log.error("MangaDex search failed for '{}': {}", keyword, e.getMessage());
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
            log.info("MangaDex fetching cover for: {}", result.getTitle());
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
            log.warn("Failed to get cover from MangaDex for {}: {}", result.getTitle(), e.getMessage());
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

    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "fryfrog-hub/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private String getMultiLangField(JsonNode node, String field) {
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isTextual()) {
            return fieldNode.asText("");
        }
        if (fieldNode.isArray() && !fieldNode.isEmpty()) {
            return fieldNode.get(0).asText("");
        }
        return "";
    }
}
