package com.fryfrog.hub.ebook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fryfrog.hub.common.service.BangumiClient;
import com.fryfrog.hub.ebook.dto.EbookScrapeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bangumi（bgm.tv）书籍元数据源（type=1）。
 * 作者取 infobox「作者/原作」，出版社取「出版社」，出版年取「发售日/date」。
 * 评分 10 分制折半映射到 0-5。
 */
@Component
@Slf4j
public class BangumiEbookProvider implements EbookMetadataProvider {

    private final BangumiClient bangumiClient;

    public BangumiEbookProvider(BangumiClient bangumiClient) {
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
    public List<EbookScrapeResult> search(String keyword) throws Exception {
        JsonNode data = bangumiClient.searchSubjects(keyword, List.of(BangumiClient.TYPE_BOOK));
        List<EbookScrapeResult> results = new ArrayList<>();
        for (JsonNode node : data) {
            EbookScrapeResult r = parseSubject(node);
            if (r != null) results.add(r);
        }
        log.info("[EbookScrape] bangumi search '{}': {} results", keyword, results.size());
        return results;
    }

    @Override
    public EbookScrapeResult fetch(String sourceId) throws Exception {
        JsonNode node = bangumiClient.getSubject(sourceId);
        return node == null ? null : parseSubject(node);
    }

    /** 条目节点 → 统一模型；无有效书名返回 null。 */
    EbookScrapeResult parseSubject(JsonNode node) {
        EbookScrapeResult r = new EbookScrapeResult();
        r.setSource(source());
        r.setSourceId(node.path("id").asText(null));
        r.setTitle(BangumiClient.firstNonBlank(
                node.path("name_cn").asText(null), node.path("name").asText(null)));
        if (r.getTitle() == null || r.getTitle().isBlank()) return null;
        r.setAuthor(BangumiClient.infoboxValue(node, "作者", "原作"));
        r.setPublisher(BangumiClient.infoboxValue(node, "出版社"));
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
