package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 悦听吧（yuetingba.cn）有声书元数据源。
 * 站点无公开 API，通过 HTML 解析实现：
 * <ul>
 *   <li>搜索：GET /Search?name={kw}，结果项 class=section-box-list-item</li>
 *   <li>详情：GET /book/detail/{uuid}/0，字段：作者/演播/集数/内容简介/封面</li>
 * </ul>
 * 站点仅 HTTP 可达。sourceId 为书籍 UUID。
 */
@Component
@Slf4j
public class YuetingbaAudiobookProvider implements AudiobookMetadataProvider {

    private final String baseUrl;

    public YuetingbaAudiobookProvider(
            @Value("${audiobook.yuetingba-base-url:http://yuetingba.cn}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String source() {
        return "yuetingba";
    }

    @Override
    public String displayName() {
        return "悦听吧";
    }

    @Override
    public List<AudiobookScrapeResult> search(String keyword) throws Exception {
        String url = baseUrl + "/Search?name=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        Document doc = Jsoup.connect(url).timeout(15000)
                .userAgent("Mozilla/5.0 (compatible; FryfrogHub/1.0)")
                .get();
        return parseSearch(doc);
    }

    List<AudiobookScrapeResult> parseSearch(Document doc) {
        List<AudiobookScrapeResult> results = new ArrayList<>();
        for (Element item : doc.select(".section-box-list-item")) {
            Element titleLink = item.selectFirst(".box-list-item-text-title a");
            if (titleLink == null) continue;
            String href = titleLink.attr("href");
            String uuid = extractUuid(href);
            if (uuid == null) continue;

            AudiobookScrapeResult r = new AudiobookScrapeResult();
            r.setSource(source());
            r.setSourceId(uuid);
            r.setTitle(titleLink.text().strip());
            r.setAuthor(siblingValue(item, ".lnr-user"));
            r.setNarrator(siblingValue(item, ".lnr-mic"));
            Element img = item.selectFirst(".box-list-item-img img");
            if (img != null) {
                r.setCoverUrl(absolute(fullSize(img.attr("src"))));
            }
            results.add(r);
        }
        log.info("[AudiobookScrape] yuetingba search: {} results", results.size());
        return results;
    }

    @Override
    public AudiobookScrapeResult fetch(String sourceId) throws Exception {
        String url = baseUrl + "/book/detail/" + sourceId + "/0";
        Document doc = Jsoup.connect(url).timeout(15000)
                .userAgent("Mozilla/5.0 (compatible; FryfrogHub/1.0)")
                .get();
        return parseDetail(doc, sourceId);
    }

    AudiobookScrapeResult parseDetail(Document doc, String sourceId) {
        AudiobookScrapeResult r = new AudiobookScrapeResult();
        r.setSource(source());
        r.setSourceId(sourceId);
        Element h1 = doc.selectFirst("h1");
        Element h2 = doc.selectFirst(".book-detail-title");
        r.setTitle((h1 != null && !h1.text().isBlank() ? h1 : h2).text().strip());
        r.setAuthor(descValue(doc, "作\\s*者"));
        r.setNarrator(descValue(doc, "演\\s*播"));
        r.setOverview(overview(doc));
        Element img = doc.selectFirst(".books-detail-img img");
        if (img != null) {
            r.setCoverUrl(absolute(fullSize(img.attr("src"))));
        }
        if (r.getTitle() == null || r.getTitle().isBlank()) {
            return null;
        }
        log.info("[AudiobookScrape] yuetingba fetch {}: {}", sourceId, r.getTitle());
        return r;
    }

    /** 详情页字段：<span class="text-desc-title">作  者：</span><span class="text-desc-content">…</span> */
    private String descValue(Document doc, String labelRegex) {
        Element label = doc.selectFirst("span.text-desc-title:matchesOwn(" + labelRegex + ")");
        if (label == null) return null;
        Element value = label.nextElementSibling();
        if (value == null) return null;
        Element a = value.selectFirst("a");
        String text = (a != null ? a.text() : value.text()).strip();
        return text.isBlank() ? null : text;
    }

    /** 搜索结果项字段：<i class="lnr-user">…</i><span title="xxx"><a>xxx</a></span> */
    private String siblingValue(Element item, String iconSelector) {
        Element icon = item.selectFirst(iconSelector);
        if (icon == null) return null;
        Element span = icon.nextElementSibling();
        while (span != null && !"span".equals(span.tagName())) {
            span = span.nextElementSibling();
        }
        if (span == null) return null;
        String text = span.text().strip();
        return text.isBlank() ? null : text;
    }

    /** 简介：<h4>内容简介：</h4> 后续 text-desc-content 内的 <p> 文本。 */
    private String overview(Document doc) {
        Element h4 = doc.selectFirst("h4:containsOwn(内容简介)");
        if (h4 == null) return null;
        Element content = h4.parent() != null ? h4.parent().nextElementSibling() : null;
        if (content == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Element p : content.select("p")) {
            String text = p.text().strip();
            if (!text.isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(text);
            }
        }
        String result = sb.toString().strip();
        return result.isBlank() ? null : result;
    }

    /** 缩略图 → 原图（去掉 _thumb 后缀，实测原图可用）。 */
    private String fullSize(String src) {
        return src != null ? src.replace("_thumb", "") : null;
    }

    private String absolute(String src) {
        if (src == null || src.isBlank()) return null;
        return src.startsWith("http") ? src : baseUrl + src;
    }

    private String extractUuid(String href) {
        if (href == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/book/detail/([0-9a-f\\-]{36})").matcher(href);
        return m.find() ? m.group(1) : null;
    }
}
