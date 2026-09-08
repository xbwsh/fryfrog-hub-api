package com.fryfrog.hub.audiobook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import com.fryfrog.hub.common.service.BangumiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bangumi（bgm.tv）有声书元数据源。
 * 有声书归三次元（type=6），部分以书籍（type=1）+标签收录，故两者都搜。
 * 作者取 infobox「原作/作者」，朗读者取「朗读者/演播/主演」。评分 10 分制折半映射到 0-5。
 */
@Component
@Slf4j
public class BangumiAudiobookProvider implements AudiobookMetadataProvider {

    private final BangumiClient bangumiClient;

    public BangumiAudiobookProvider(BangumiClient bangumiClient) {
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
    public List<AudiobookScrapeResult> search(String keyword) throws Exception {
        JsonNode data = bangumiClient.searchSubjects(keyword,
                List.of(BangumiClient.TYPE_BOOK, BangumiClient.TYPE_REAL));
        List<AudiobookScrapeResult> results = new ArrayList<>();
        for (JsonNode node : data) {
            AudiobookScrapeResult r = parseSubject(node);
            if (r != null) results.add(r);
        }
        log.info("[AudiobookScrape] bangumi search '{}': {} results", keyword, results.size());
        return results;
    }

    @Override
    public AudiobookScrapeResult fetch(String sourceId) throws Exception {
        JsonNode node = bangumiClient.getSubject(sourceId);
        return node == null ? null : parseSubject(node);
    }

    /** 条目节点 → 统一模型；无有效书名返回 null。 */
    AudiobookScrapeResult parseSubject(JsonNode node) {
        AudiobookScrapeResult r = new AudiobookScrapeResult();
        r.setSource(source());
        r.setSourceId(node.path("id").asText(null));
        r.setTitle(BangumiClient.firstNonBlank(
                node.path("name_cn").asText(null), node.path("name").asText(null)));
        if (r.getTitle() == null || r.getTitle().isBlank()) return null;
        r.setAuthor(BangumiClient.infoboxValue(node, "作者", "原作"));
        r.setNarrator(BangumiClient.infoboxValue(node, "朗读者", "演播", "主演"));
        r.setOverview(BangumiClient.blankToNull(node.path("summary").asText(null)));
        r.setCoverUrl(BangumiClient.blankToNull(node.path("images").path("large").asText(null)));

        double score = node.path("rating").path("score").asDouble(0);
        if (score > 0) {
            r.setRating(Math.round(score / 2 * 10) / 10.0);
        }
        String date = node.path("date").asText("");
        if (date.length() >= 4) {
            try {
                r.setYear(Integer.parseInt(date.substring(0, 4)));
            } catch (NumberFormatException ignored) {
            }
        }
        return r;
    }
}
