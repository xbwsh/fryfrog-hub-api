package com.fryfrog.hub.audiobook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import com.fryfrog.hub.common.service.BangumiClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class BangumiAudiobookProviderTest {

    private final BangumiAudiobookProvider provider =
            new BangumiAudiobookProvider(new BangumiClient(null, "https://api.bgm.tv"));

    private final ObjectMapper mapper = new ObjectMapper();

    /** 三次元有声书：主演=朗读者，原作=作者，infobox 链接为数组形态 */
    private final String detailJson = """
            {
              "id": 521123,
              "type": 6,
              "name": "魔道祖师 有声书",
              "name_cn": "",
              "date": "2019-07-12",
              "summary": "由声之优制作的有声书。",
              "images": {"large": "https://lain.bgm.tv/pic/cover/l/0f/ca/521123_P1Je1.jpg"},
              "rating": {"rank": 0, "total": 10, "score": 7.6},
              "infobox": [
                {"key": "集数", "value": "157"},
                {"key": "主演", "value": "张震"},
                {"key": "原作", "value": [{"v": "墨香铜臭"}]},
                {"key": "链接", "value": [{"k": "喜马拉雅", "v": "https://www.ximalaya.com/album/25069034"}]}
              ]
            }
            """;

    @Test
    void parseSubjectMapsCoreFields() throws Exception {
        JsonNode node = mapper.readTree(detailJson);
        AudiobookScrapeResult r = provider.parseSubject(node);

        assertThat(r).isNotNull();
        assertThat(r.getSource()).isEqualTo("bangumi");
        assertThat(r.getSourceId()).isEqualTo("521123");
        assertThat(r.getTitle()).isEqualTo("魔道祖师 有声书");
        assertThat(r.getAuthor()).isEqualTo("墨香铜臭");
        assertThat(r.getNarrator()).isEqualTo("张震");
        assertThat(r.getOverview()).isEqualTo("由声之优制作的有声书。");
        assertThat(r.getCoverUrl()).isEqualTo("https://lain.bgm.tv/pic/cover/l/0f/ca/521123_P1Je1.jpg");
        assertThat(r.getRating()).isEqualTo(3.8);
        assertThat(r.getYear()).isEqualTo(2019);
    }

    @Test
    void searchResponseParsesToResults() throws Exception {
        String resp = """
                {"total": 1, "limit": 20, "offset": 0,
                 "data": [%s, {"id": 9585, "type": 1, "name": "", "name_cn": ""}]}
                """.formatted(detailJson);
        JsonNode data = mapper.readTree(resp).path("data");

        var results = new java.util.ArrayList<AudiobookScrapeResult>();
        for (JsonNode node : data) {
            var r = provider.parseSubject(node);
            if (r != null) results.add(r);
        }

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSourceId()).isEqualTo("521123");
    }

    @Test
    void multiValueInfoboxJoinsWithChineseComma() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 1, "name": "多人有声剧",
                 "infobox": [
                   {"key": "主演", "value": [{"v": "徐宇隆"}, {"v": "风袖"}, {"v": "家明"}]},
                   {"key": "作者", "value": [{"k": "别名", "v": "某作者"}]}
                 ]}
                """);
        AudiobookScrapeResult r = provider.parseSubject(node);

        assertThat(r.getNarrator()).isEqualTo("徐宇隆、风袖、家明");
        assertThat(r.getAuthor()).isEqualTo("某作者");
    }

    @Test
    void zeroScoreAndMissingFieldsYieldNulls() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 2, "name": "无评分条目", "rating": {"score": 0}, "date": "invalid"}
                """);
        AudiobookScrapeResult r = provider.parseSubject(node);

        assertThat(r).isNotNull();
        assertThat(r.getRating()).isNull();
        assertThat(r.getYear()).isNull();
        assertThat(r.getAuthor()).isNull();
        assertThat(r.getNarrator()).isNull();
    }

    @Test
    void nameCnPreferredOverName() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 3, "name": "ティアムーン帝国物語", "name_cn": "堤亚穆帝国物语"}
                """);
        AudiobookScrapeResult r = provider.parseSubject(node);

        assertThat(r.getTitle()).isEqualTo("堤亚穆帝国物语");
    }
}
