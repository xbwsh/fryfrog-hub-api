package com.fryfrog.hub.comic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.common.service.BangumiClient;
import com.fryfrog.hub.comic.dto.ComicScrapeResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class BangumiComicProviderTest {

    private final BangumiComicProvider provider =
            new BangumiComicProvider(new BangumiClient(null, "https://api.bgm.tv"));

    private final ObjectMapper mapper = new ObjectMapper();

    /** 书籍条目（漫画）：infobox 作者/原作 */
    private final String detailJson = """
            {
              "id": 25581,
              "type": 1,
              "name": "\u9032\u6483\u306e\u5de8\u4eba",
              "name_cn": "\u8fdb\u51fb\u7684\u5de8\u4eba",
              "date": "2009-09-09",
              "summary": "\u4eba\u7c7b\u9ad8\u5899\u5185\u7684\u5b89\u7a33\u65e5\u5b50\u88ab\u7a81\u7834\u4e86\u3002",
              "images": {"large": "https://lain.bgm.tv/pic/cover/l/xx/yy/25581.jpg"},
              "rating": {"rank": 1, "total": 3000, "score": 9.0},
              "infobox": [
                {"key": "\u4f5c\u8005", "value": "\u8c0f\u5c71\u521b"},
                {"key": "\u539f\u4f5c", "value": "\u8c0f\u5c71\u521b"},
                {"key": "\u53d1\u552e\u65e5", "value": "2009-09-09"}
              ]
            }
            """;

    @Test
    void parseSubjectMapsCoreFields() throws Exception {
        JsonNode node = mapper.readTree(detailJson);
        ComicScrapeResult r = provider.parseSubject(node);

        assertThat(r).isNotNull();
        assertThat(r.getSource()).isEqualTo("bangumi");
        assertThat(r.getSourceId()).isEqualTo("25581");
        assertThat(r.getTitle()).isEqualTo("进击的巨人");
        assertThat(r.getAuthor()).isEqualTo("谏山创");
        assertThat(r.getPubYear()).isEqualTo(2009);
        assertThat(r.getRating()).isEqualTo(4.5);
        assertThat(r.getCoverUrl()).isEqualTo("https://lain.bgm.tv/pic/cover/l/xx/yy/25581.jpg");
    }

    @Test
    void missingFieldsYieldNulls() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 2, "name": "\u65e0\u5143\u6570\u636e"}
                """);
        ComicScrapeResult r = provider.parseSubject(node);

        assertThat(r).isNotNull();
        assertThat(r.getAuthor()).isNull();
        assertThat(r.getRating()).isNull();
        assertThat(r.getPubYear()).isNull();
    }

    @Test
    void blankNameReturnsNull() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 3, "name": ""}
                """);
        assertThat(provider.parseSubject(node)).isNull();
    }
}
