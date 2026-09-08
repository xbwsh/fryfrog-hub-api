package com.fryfrog.hub.comic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fryfrog.hub.common.service.BangumiClient;
import com.fryfrog.hub.comic.dto.ComicScrapeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bangumi（bgm.tv）漫画元数据源（type=1 书籍）。
 * 作者取 infobox「作者/原作」，评分 10 分制折半映射到 0-5。
 */
@Component
@Slf4j
public class BangumiComicProvider implements ComicMetadataProvider {

    private final BangumiClient bangumiClient;

    public BangumiComicProvider(BangumiClient bangumiClient) {
        this.bangumiClient = bangumiClient;
    }

    @Override
    public String source() {
        return "bangumi";
    }

    @Override
    public String displayName() {
        return "Bangumi";
    }

    @Override
    public List<ComicScrapeResult> search(String keyword) throws Exception {
        JsonNode data = bangumiClient.searchSubjects(keyword, List.of(BangumiClient.TYPE_BOOK));
        List<ComicScrapeResult> results = new ArrayList<>();
        for (JsonNode node : data) {
            ComicScrapeResult r = parseSubject(node);
            if (r != null) results.add(r);
        }
        log.info("[ComicScrape] bangumi search '{}': {} results", keyword, results.size());
        return results;
    }

    @Override
    public ComicScrapeResult fetch(String sourceId) throws Exception {
        JsonNode node = bangumiClient.getSubject(sourceId);
        return node == null ? null : parseSubject(node);
    }

    /** 条目节点 → 统一模型；无有效书名返回 null。 */
    ComicScrapeResult parseSubject(JsonNode node) {
        ComicScrapeResult r = new ComicScrapeResult();
        r.setSource(source());
        r.setSourceId(node.path("id").asText(null));
        r.setTitle(BangumiClient.firstNonBlank(
                node.path("name_cn").asText(null), node.path("name").asText(null)));
        if (r.getTitle() == null || r.getTitle().isBlank()) return null;
        r.setAuthor(BangumiClient.infoboxValue(node, "作者", "原作"));
        r.setOverview(BangumiClient.blankToNull(node.path("summary").asText(null)));
        r.setCoverUrl(BangumiClient.blankToNull(node.path("images").path("large").asText(null)));

        double score = node.path("rating").path("score").asDouble(0);
        if (score > 0) {
            r.setRating(Math.round(score / 2 * 10) / 10.0);
        }
        String date = BangumiClient.firstNonBlank(
                node.path("date").asText(null),
                BangumiClient.infoboxValue(node, "发售日"));
        if (date != null && date.length() >= 4) {
            try {
                r.setPubYear(Integer.parseInt(date.substring(0, 4)));
            } catch (NumberFormatException ignored) {
            }
        }
        return r;
    }
}
