package com.fryfrog.hub.ebook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContentCleaner {

    private final ObjectMapper objectMapper;

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MULTIPLE_NEWLINE_PATTERN = Pattern.compile("\\n{3,}");
    private static final Pattern MULTIPLE_SPACE_PATTERN = Pattern.compile(" {2,}");
    private static final Pattern ADVERTISEMENT_PATTERNS = Pattern.compile(
            "(?:最新章节|手机阅读|请记住网址|加入书签|推荐票|月票|打赏|催更|更新时间|本章未完|点击下一页)",
            Pattern.CASE_INSENSITIVE
    );

    public String clean(String html, String cleanRuleJson) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        try {
            Document doc = Jsoup.parse(html);
            String content = extractContent(doc, cleanRuleJson);
            content = removeHtmlTags(content);
            content = removeAdvertisements(content);
            content = cleanFormat(content);
            return content.trim();
        } catch (Exception e) {
            log.debug("净化内容失败: {}", e.getMessage());
            return removeHtmlTags(html).trim();
        }
    }

    public String cleanForDisplay(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        try {
            Document doc = Jsoup.parse(html);
            doc.select("script, style, iframe, noscript").remove();
            doc.select("[class*='ad'], [class*='Ad'], [id*='ad'], [id*='Ad']").remove();
            doc.select("[style*='display:none'], [style*='display: none']").remove();
            return doc.body().html();
        } catch (Exception e) {
            log.debug("净化显示内容失败: {}", e.getMessage());
            return html;
        }
    }

    private String extractContent(Document doc, String cleanRuleJson) {
        if (cleanRuleJson == null || cleanRuleJson.isEmpty()) {
            return doc.body().html();
        }

        try {
            JsonNode rule = objectMapper.readTree(cleanRuleJson);

            JsonNode removeNodes = rule.get("remove");
            if (removeNodes != null && removeNodes.isArray()) {
                for (JsonNode node : removeNodes) {
                    doc.select(node.asText()).remove();
                }
            }

            JsonNode contentSelector = rule.get("content");
            if (contentSelector != null && !contentSelector.isNull()) {
                Elements elements = doc.select(contentSelector.asText());
                StringBuilder sb = new StringBuilder();
                for (Element element : elements) {
                    sb.append(element.html()).append("\n");
                }
                return sb.toString();
            }

            return doc.body().html();
        } catch (Exception e) {
            log.debug("解析净化规则失败: {}", e.getMessage());
            return doc.body().html();
        }
    }

    private String removeHtmlTags(String html) {
        if (html == null) return "";
        return HTML_TAG_PATTERN.matcher(html).replaceAll("");
    }

    private String removeAdvertisements(String content) {
        if (content == null) return "";
        return ADVERTISEMENT_PATTERNS.matcher(content).replaceAll("");
    }

    private String cleanFormat(String content) {
        if (content == null) return "";
        content = MULTIPLE_NEWLINE_PATTERN.matcher(content).replaceAll("\n\n");
        content = MULTIPLE_SPACE_PATTERN.matcher(content).replaceAll(" ");
        content = content.replace("\u00A0", " ");
        content = content.replace("\u3000", " ");
        return content;
    }

    public String decodeLazyLoadImage(String src, String decodeAttr) {
        if (src == null || src.isEmpty()) {
            return src;
        }

        if (decodeAttr == null || decodeAttr.isEmpty()) {
            return src;
        }

        try {
            Pattern pattern = Pattern.compile(decodeAttr);
            Matcher matcher = pattern.matcher(src);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.debug("解码懒加载图片失败: {}", e.getMessage());
        }

        return src;
    }
}
