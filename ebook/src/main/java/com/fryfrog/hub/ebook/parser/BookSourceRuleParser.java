package com.fryfrog.hub.ebook.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.ebook.model.rule.BookInfoRule;
import com.fryfrog.hub.ebook.model.rule.BookSourceRule;
import com.fryfrog.hub.ebook.model.rule.ContentRule;
import com.fryfrog.hub.ebook.model.rule.SearchRule;
import com.fryfrog.hub.ebook.model.rule.TocRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookSourceRuleParser {

    private final ObjectMapper objectMapper;

    private static final Pattern CSS_SELECTOR_PATTERN = Pattern.compile("^(\\S+?)(?:\\s*\\[(.+?)\\])?(?:\\s*>\\s*(.+))?$");
    private static final Pattern ATTR_PATTERN = Pattern.compile("@(\\w+)(?:\\((.+?)\\))?");

    public BookSourceRule parse(String ruleJson) {
        try {
            JsonNode root = objectMapper.readTree(ruleJson);
            return objectMapper.convertValue(root, BookSourceRule.class);
        } catch (Exception e) {
            log.error("解析书源规则失败", e);
            throw new RuntimeException("解析书源规则失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, String>> search(String html, SearchRule rule, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        List<Map<String, String>> results = new ArrayList<>();

        Elements bookElements = doc.select(convertLegadoSelector(rule.getBookList()));
        for (Element bookElement : bookElements) {
            Map<String, String> book = new HashMap<>();
            book.put("name", extractText(bookElement, rule.getName()));
            book.put("author", extractText(bookElement, rule.getAuthor()));
            book.put("coverUrl", extractUrl(bookElement, rule.getCoverUrl(), baseUrl));
            book.put("bookUrl", extractUrl(bookElement, rule.getBookUrl(), baseUrl));
            book.put("intro", extractText(bookElement, rule.getIntro()));
            book.put("kind", extractText(bookElement, rule.getKind()));
            book.put("lastChapter", extractText(bookElement, rule.getLastChapter()));
            results.add(book);
        }

        return results;
    }

    public Map<String, String> getBookInfo(String html, BookInfoRule rule, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        Map<String, String> info = new HashMap<>();

        info.put("name", extractText(doc, rule.getName()));
        info.put("author", extractText(doc, rule.getAuthor()));
        info.put("intro", extractText(doc, rule.getIntro()));
        info.put("coverUrl", extractUrl(doc, rule.getCoverUrl(), baseUrl));
        info.put("tocUrl", extractUrl(doc, rule.getTocUrl(), baseUrl));
        info.put("wordCount", extractText(doc, rule.getWordCount()));
        info.put("lastChapter", extractText(doc, rule.getLastChapter()));
        info.put("kind", extractText(doc, rule.getKind()));

        return info;
    }

    public List<Map<String, String>> getChapterList(String html, TocRule rule, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        List<Map<String, String>> chapters = new ArrayList<>();

        Elements chapterElements = doc.select(convertLegadoSelector(rule.getChapterList()));
        for (Element chapterElement : chapterElements) {
            Map<String, String> chapter = new HashMap<>();
            chapter.put("chapterName", extractText(chapterElement, rule.getChapterName()));
            chapter.put("chapterUrl", extractUrl(chapterElement, rule.getChapterUrl(), baseUrl));
            chapters.add(chapter);
        }

        if (Boolean.TRUE.equals(rule.getIsVolume())) {
            List<Map<String, String>> reversed = new ArrayList<>(chapters);
            java.util.Collections.reverse(reversed);
            return reversed;
        }

        return chapters;
    }

    public String getContent(String html, ContentRule rule, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        StringBuilder content = new StringBuilder();

        Elements contentElements = doc.select(convertLegadoSelector(rule.getContent()));
        for (Element element : contentElements) {
            content.append(element.html()).append("\n");
        }

        if (rule.getReplaceRegex() != null && !rule.getReplaceRegex().isEmpty()) {
            String replaceTo = rule.getReplaceTo() != null ? rule.getReplaceTo() : "";
            content = new StringBuilder(content.toString().replaceAll(rule.getReplaceRegex(), replaceTo));
        }

        if (rule.getFilter() != null && !rule.getFilter().isEmpty()) {
            String filtered = content.toString().replaceAll(rule.getFilter(), "");
            content = new StringBuilder(filtered);
        }

        return content.toString();
    }

    public String getNextContentUrl(String html, ContentRule rule, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        if (rule.getNextContentUrl() == null || rule.getNextContentUrl().isEmpty()) {
            return null;
        }
        return extractUrl(doc, rule.getNextContentUrl(), baseUrl);
    }

    public String getNextTocUrl(String html, TocRule rule, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        if (rule.getNextTocUrl() == null || rule.getNextTocUrl().isEmpty()) {
            return null;
        }
        return extractUrl(doc, rule.getNextTocUrl(), baseUrl);
    }

    private String extractText(Element parent, String selector) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }

        try {
            ParsedSelector parsed = parseSelector(selector);
            Element element = parent.select(parsed.selector).first();
            if (element == null) {
                return "";
            }

            if (parsed.attrName != null) {
                return element.attr(parsed.attrName);
            }

            if (parsed.subSelector != null) {
                Element subElement = element.select(parsed.subSelector).first();
                return subElement != null ? subElement.text() : "";
            }

            return element.text();
        } catch (Exception e) {
            log.debug("解析文本失败: selector={}, error={}", selector, e.getMessage());
            return "";
        }
    }

    private String extractUrl(Element parent, String selector, String baseUrl) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }

        try {
            ParsedSelector parsed = parseSelector(selector);
            Element element = parent.select(parsed.selector).first();
            if (element == null) {
                return "";
            }

            String url;
            if (parsed.attrName != null) {
                url = element.attr(parsed.attrName);
            } else {
                url = element.attr("href");
            }

            if (url == null || url.isEmpty()) {
                return "";
            }

            return resolveUrl(url, baseUrl);
        } catch (Exception e) {
            log.debug("解析URL失败: selector={}, error={}", selector, e.getMessage());
            return "";
        }
    }

    private String extractText(Document doc, String selector) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }

        try {
            ParsedSelector parsed = parseSelector(selector);
            Element element = doc.select(parsed.selector).first();
            if (element == null) {
                return "";
            }

            if (parsed.attrName != null) {
                return element.attr(parsed.attrName);
            }

            if (parsed.subSelector != null) {
                Element subElement = element.select(parsed.subSelector).first();
                return subElement != null ? subElement.text() : "";
            }

            return element.text();
        } catch (Exception e) {
            log.debug("解析文本失败: selector={}, error={}", selector, e.getMessage());
            return "";
        }
    }

    private String extractUrl(Document doc, String selector, String baseUrl) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }

        try {
            ParsedSelector parsed = parseSelector(selector);
            Element element = doc.select(parsed.selector).first();
            if (element == null) {
                return "";
            }

            String url;
            if (parsed.attrName != null) {
                url = element.attr(parsed.attrName);
            } else {
                url = element.attr("href");
            }

            if (url == null || url.isEmpty()) {
                return "";
            }

            return resolveUrl(url, baseUrl);
        } catch (Exception e) {
            log.debug("解析URL失败: selector={}, error={}", selector, e.getMessage());
            return "";
        }
    }

    private String convertLegadoSelector(String selector) {
        if (selector == null || selector.isEmpty()) {
            return selector;
        }
        String result = selector;
        result = result.replaceAll("(?<!\\w)class\\.", ".");
        result = result.replaceAll("(?<!\\w)id:", "#");
        result = result.replaceAll("(?<!\\w)tag\\.", "");
        result = result.replaceAll("!\\d+", "");
        result = result.replace("@", " ");
        return result;
    }

    private ParsedSelector parseSelector(String selector) {
        String converted = convertLegadoSelector(selector);
        Matcher matcher = CSS_SELECTOR_PATTERN.matcher(converted);
        if (!matcher.matches()) {
            return new ParsedSelector(converted, null, null);
        }

        String cssSelector = matcher.group(1);
        String attrPart = matcher.group(2);
        String subSelector = matcher.group(3);

        String attrName = null;
        String attrParam = null;

        if (attrPart != null) {
            Matcher attrMatcher = ATTR_PATTERN.matcher(attrPart);
            if (attrMatcher.matches()) {
                attrName = attrMatcher.group(1);
                attrParam = attrMatcher.group(2);
            }
        }

        return new ParsedSelector(cssSelector, attrName, subSelector);
    }

    private String resolveUrl(String url, String baseUrl) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        try {
            java.net.URI baseUri = new java.net.URI(baseUrl);
            java.net.URI resolved = baseUri.resolve(url);
            return resolved.toString();
        } catch (Exception e) {
            log.debug("解析URL失败: url={}, baseUrl={}, error={}", url, baseUrl, e.getMessage());
            return url;
        }
    }

    private static class ParsedSelector {
        final String selector;
        final String attrName;
        final String subSelector;

        ParsedSelector(String selector, String attrName, String subSelector) {
            this.selector = selector;
            this.attrName = attrName;
            this.subSelector = subSelector;
        }
    }
}
