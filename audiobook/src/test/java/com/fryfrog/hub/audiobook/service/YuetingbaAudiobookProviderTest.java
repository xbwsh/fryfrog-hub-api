package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class YuetingbaAudiobookProviderTest {

    private static final String UUID = "3a074091-9338-a6e1-d74c-ef1142f0734c";

    private final YuetingbaAudiobookProvider provider =
            new YuetingbaAudiobookProvider("http://yuetingba.cn");

    private final String searchHtml = """
            <html><body><div class="section-box-list-item">
              <div class="box-list-item-img">
                <a href="/book/detail/%s/0"><img src="/myfiles/x_thumb.webp" class="img-thumbnail"></a>
              </div>
              <div class="box-list-item-text">
                <div class="box-list-item-text-title"><a href="/book/detail/%s/0">剑来</a></div>
                <i class=" lnr lnr-user" title="作者"></i>
                <span title="烽火戏诸侯"><a href="#">烽火戏诸侯</a></span>
                <i class=" lnr lnr-mic" title="主播"></i>
                <span title="大斌"><a href="#">大斌</a></span>
              </div>
            </div></body></html>
            """.formatted(UUID, UUID);

    private final String detailHtml = """
            <html><body>
              <h2 class="book-detail-title">剑来</h2>
              <h1 class="hidden-xs">剑来</h1>
              <div class="books-detail-img"><img src="http://yuetingba.cn/myfiles/x_thumb.webp"></div>
              <div class="text-desc-detail">
                <div><span class="text-desc-title">作  者：</span><span class="text-desc-content"><a href="#">烽火戏诸侯</a></span></div>
                <div><span class="text-desc-title">演  播：</span><span class="text-desc-content"><a href="#">大斌</a></span></div>
                <div><span class="text-desc-title">集  数：</span><span class="text-desc-content">5342</span></div>
              </div>
              <div class="col-md-12 text-desc-title"><h4>内容简介：</h4></div>
              <div class="col-md-12 text-desc-content">
                <p>《剑来》——烽火戏诸侯的仙侠巨著</p>
                <p>大千世界，无奇不有。</p>
              </div>
            </body></html>
            """;

    @Test
    void searchParsesResultItems() {
        Document doc = Jsoup.parse(searchHtml);
        List<AudiobookScrapeResult> results = provider.parseSearch(doc);

        assertThat(results).hasSize(1);
        AudiobookScrapeResult r = results.get(0);
        assertThat(r.getSource()).isEqualTo("yuetingba");
        assertThat(r.getSourceId()).isEqualTo(UUID);
        assertThat(r.getTitle()).isEqualTo("剑来");
        assertThat(r.getAuthor()).isEqualTo("烽火戏诸侯");
        assertThat(r.getNarrator()).isEqualTo("大斌");
        // 缩略图自动升级为原图
        assertThat(r.getCoverUrl()).isEqualTo("http://yuetingba.cn/myfiles/x.webp");
    }

    @Test
    void fetchParsesDetailPage() {
        AudiobookScrapeResult r = provider.parseDetail(Jsoup.parse(detailHtml), UUID);

        assertThat(r).isNotNull();
        assertThat(r.getTitle()).isEqualTo("剑来");
        assertThat(r.getAuthor()).isEqualTo("烽火戏诸侯");
        assertThat(r.getNarrator()).isEqualTo("大斌");
        assertThat(r.getOverview()).contains("仙侠巨著").contains("大千世界");
        assertThat(r.getCoverUrl()).isEqualTo("http://yuetingba.cn/myfiles/x.webp");
    }

    @Test
    void detailWithoutH1FallsBackToTitleClass() {
        String html = detailHtml.replace("<h1 class=\"hidden-xs\">剑来</h1>", "");
        AudiobookScrapeResult r = provider.parseDetail(Jsoup.parse(html), UUID);
        assertThat(r.getTitle()).isEqualTo("剑来");
    }
}
