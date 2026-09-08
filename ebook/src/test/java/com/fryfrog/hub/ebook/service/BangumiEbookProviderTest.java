package com.fryfrog.hub.ebook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.common.service.BangumiClient;
import com.fryfrog.hub.ebook.dto.EbookScrapeResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class BangumiEbookProviderTest {

    private final BangumiEbookProvider provider =
            new BangumiEbookProvider(new BangumiClient(null, "https://api.bgm.tv"));

    private final ObjectMapper mapper = new ObjectMapper();

    /** 书籍条目：infobox 作者/出版社/发售日 */
    private final String detailJson = """
            {
              "id": 9585,
              "type": 1,
              "name": "\u4e09\u4f53",
              "name_cn": "",
              "date": "2008-01-01",
              "summary": "\u6587\u5316\u5927\u9769\u547d\u5982\u706b\u5982\u837c\u8fdb\u884c\u7684\u540c\u65f6\u3002",
              "images": {"large": "https://lain.bgm.tv/pic/cover/l/da/52/9585_ZhcrW.jpg"},
              "rating": {"rank": 0, "total": 2289, "score": 8.4},
              "infobox": [
                {"key": "\u4f5c\u8005", "value": "\u5218\u6148\u6b23"},
                {"key": "\u51fa\u7248\u793e", "value": [{"v": "\u91cd\u5e86\u51fa\u7248\u793e"}]},
                {"key": "\u53d1\u552e\u65e5", "value": "2008-01-01"},
                {"key": "ISBN", "value": "9787536692930"}
              ]
            }
            """;

    @Test
    void parseSubjectMapsCoreFields() throws Exception {
        JsonNode node = mapper.readTree(detailJson);
        EbookScrapeResult r = provider.parseSubject(node);

        assertThat(r).isNotNull();
        assertThat(r.getSource()).isEqualTo("bangumi");
        assertThat(r.getSourceId()).isEqualTo("9585");
        assertThat(r.getTitle()).isEqualTo("三体");
        assertThat(r.getAuthor()).isEqualTo("刘慈欣");
        assertThat(r.getPublisher()).isEqualTo("重庆出版社");
        assertThat(r.getPubYear()).isEqualTo(2008);
        assertThat(r.getRating()).isEqualTo(4.2);
        assertThat(r.getCoverUrl()).isEqualTo("https://lain.bgm.tv/pic/cover/l/da/52/9585_ZhcrW.jpg");
    }

    @Test
    void nameCnPreferredOverName() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 1, "name": "\u30c1\u30e5\u30fc\u30ea\u30f3\u30ac\u30a4\u30e9\u30f3", "name_cn": "图灵国\u5728"}
                """);
        EbookScrapeResult r = provider.parseSubject(node);
        assertThat(r.getTitle()).isEqualTo("图灵国\u5728");
    }

    @Test
    void missingFieldsYieldNulls() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 2, "name": "\u65e0\u8bc4\u5206\u6761\u76ee"}
                """);
        EbookScrapeResult r = provider.parseSubject(node);

        assertThat(r).isNotNull();
        assertThat(r.getAuthor()).isNull();
        assertThat(r.getPublisher()).isNull();
        assertThat(r.getRating()).isNull();
        assertThat(r.getPubYear()).isNull();
    }

    @Test
    void blankNameReturnsNull() throws Exception {
        JsonNode node = mapper.readTree("""
                {"id": 3, "name": "", "name_cn": ""}
                """);
        assertThat(provider.parseSubject(node)).isNull();
    }
}
