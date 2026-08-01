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
import java.util.regex.PatternSyntaxException;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookSourceRuleParser {

    private final ObjectMapper objectMapper;

    private static final Pattern CSS_SELECTOR_PATTERN = Pattern.compile("^(\\S+?)(?:\\s*\\[(.+?)\\])?(?:\\s*>\\s*(.+))?$");
    private static final Pattern ATTR_PATTERN = Pattern.compile("@(\\w+)(?:\\((.+?)\\))?");
    private static final Pattern JS_BLOCK_PATTERN = Pattern.compile("<js>[\\s\\S]*?</js>", Pattern.CASE_INSENSITIVE);

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
        String bookListSelector = cleanLegadoSelector(rule.getBookList());
        if (bookListSelector.isEmpty()) {
            return List.of();
        }

        if (isJsonPath(bookListSelector) && looksLikeJson(html)) {
            return searchJson(html, rule, baseUrl);
        }

        Document doc = Jsoup.parse(html, baseUrl);
        List<Map<String, String>> results = new ArrayList<>();

        Elements bookElements = doc.select(convertLegadoSelector(bookListSelector));
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

    private List<Map<String, String>> searchJson(String json, SearchRule rule, String baseUrl) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String bookListSelector = cleanLegadoSelector(rule.getBookList());
            JsonNode booksNode = resolveJsonPathWithFallback(root, bookListSelector);

            if (booksNode == null) {
                return List.of();
            }

            List<Map<String, String>> results = new ArrayList<>();
            if (booksNode.isArray()) {
                for (JsonNode bookNode : booksNode) {
                    results.add(extractJsonBookInfo(bookNode, rule));
                }
            } else if (booksNode.isObject()) {
                results.add(extractJsonBookInfo(booksNode, rule));
            }

            return results;
        } catch (Exception e) {
            log.debug("JSON搜索解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> extractJsonBookInfo(JsonNode bookNode, SearchRule rule) {
        Map<String, String> book = new HashMap<>();
        book.put("name", extractJsonField(bookNode, rule.getName()));
        book.put("author", extractJsonField(bookNode, rule.getAuthor()));
        book.put("coverUrl", extractJsonField(bookNode, rule.getCoverUrl()));
        book.put("bookUrl", extractJsonField(bookNode, rule.getBookUrl()));
        book.put("intro", extractJsonField(bookNode, rule.getIntro()));
        book.put("kind", extractJsonField(bookNode, rule.getKind()));
        book.put("lastChapter", extractJsonField(bookNode, rule.getLastChapter()));
        book.put("wordCount", extractJsonField(bookNode, rule.getWordCount()));
        return book;
    }

    private String extractJsonField(JsonNode node, String selector) {
        if (selector == null || selector.isEmpty() || node == null) {
            return "";
        }

        String cleaned = cleanLegadoSelector(selector);
        if (cleaned.isEmpty()) {
            return "";
        }

        if (isJsonPath(cleaned)) {
            JsonNode result = resolveJsonPathWithFallback(node, cleaned);
            return result != null ? result.asText("") : "";
        }

        JsonNode fieldNode = node.get(cleaned);
        return fieldNode != null ? fieldNode.asText("") : "";
    }

    private boolean isJsonPath(String selector) {
        return selector != null && selector.startsWith("$");
    }

    private boolean looksLikeJson(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private JsonNode resolveJsonPathWithFallback(JsonNode root, String jsonPath) {
        String[] paths = jsonPath.split("\\|\\|");
        for (String path : paths) {
            JsonNode result = navigateJsonPath(root, path.trim());
            if (result != null && !result.isMissingNode() && !result.isNull()) {
                return result;
            }
        }
        return null;
    }

    private JsonNode navigateJsonPath(JsonNode node, String path) {
        if (node == null || path == null || path.isEmpty()) {
            return node;
        }

        String[] parts = splitJsonPath(path);
        JsonNode current = node;

        for (String part : parts) {
            if (current == null) {
                return null;
            }

            if (part.isEmpty() || part.equals("$")) {
                continue;
            }

            int bracketStart = part.indexOf('[');
            if (bracketStart >= 0) {
                String fieldName = part.substring(0, bracketStart);
                String indexStr = part.substring(bracketStart + 1).replace("]", "");

                if (!fieldName.isEmpty()) {
                    current = current.get(fieldName);
                }

                if (current == null) {
                    return null;
                }

                if (indexStr.equals("*")) {
                    return current;
                }

                try {
                    int index = Integer.parseInt(indexStr);
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                current = current.get(part);
            }
        }

        return current;
    }

    private String[] splitJsonPath(String path) {
        String trimmed = path.strip();
        if (trimmed.startsWith("$.")) {
            trimmed = trimmed.substring(2);
        } else if (trimmed.startsWith("$")) {
            trimmed = trimmed.substring(1);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBracket = false;

        for (char c : trimmed.toCharArray()) {
            if (c == '[') {
                inBracket = true;
                current.append(c);
            } else if (c == ']') {
                inBracket = false;
                current.append(c);
            } else if (c == '.' && !inBracket) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }

    public Map<String, String> getBookInfo(String html, BookInfoRule rule, String baseUrl) {
        if (looksLikeJson(html)) {
            return getBookInfoJson(html, rule, baseUrl);
        }

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

    private Map<String, String> getBookInfoJson(String json, BookInfoRule rule, String baseUrl) {
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, String> info = new HashMap<>();

            info.put("name", extractJsonField(root, rule.getName()));
            info.put("author", extractJsonField(root, rule.getAuthor()));
            info.put("intro", extractJsonField(root, rule.getIntro()));
            info.put("coverUrl", resolveUrl(extractJsonField(root, rule.getCoverUrl()), baseUrl));
            info.put("tocUrl", resolveUrl(extractJsonField(root, rule.getTocUrl()), baseUrl));
            info.put("wordCount", extractJsonField(root, rule.getWordCount()));
            info.put("lastChapter", extractJsonField(root, rule.getLastChapter()));
            info.put("kind", extractJsonField(root, rule.getKind()));

            return info;
        } catch (Exception e) {
            log.debug("JSON书籍信息解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    public List<Map<String, String>> getChapterList(String html, TocRule rule, String baseUrl) {
        String chapterListSelector = cleanLegadoSelector(rule.getChapterList());
        if (chapterListSelector.isEmpty()) {
            return List.of();
        }

        if (isJsonPath(chapterListSelector) && looksLikeJson(html)) {
            return getChapterListJson(html, rule, baseUrl);
        }

        Document doc = Jsoup.parse(html, baseUrl);
        List<Map<String, String>> chapters = new ArrayList<>();

        Elements chapterElements = doc.select(convertLegadoSelector(chapterListSelector));
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

    private List<Map<String, String>> getChapterListJson(String json, TocRule rule, String baseUrl) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String chapterListSelector = cleanLegadoSelector(rule.getChapterList());
            JsonNode chaptersNode = resolveJsonPathWithFallback(root, chapterListSelector);

            if (chaptersNode == null) {
                return List.of();
            }

            List<Map<String, String>> chapters = new ArrayList<>();
            if (chaptersNode.isArray()) {
                for (JsonNode chapterNode : chaptersNode) {
                    Map<String, String> chapter = new HashMap<>();
                    chapter.put("chapterName", extractJsonField(chapterNode, rule.getChapterName()));
                    chapter.put("chapterUrl", resolveUrl(extractJsonField(chapterNode, rule.getChapterUrl()), baseUrl));
                    chapters.add(chapter);
                }
            }

            if (Boolean.TRUE.equals(rule.getIsVolume())) {
                java.util.Collections.reverse(chapters);
            }

            return chapters;
        } catch (Exception e) {
            log.debug("JSON目录解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    public String getContent(String html, ContentRule rule, String baseUrl) {
        String contentSelector = cleanLegadoSelector(rule.getContent());
        if (contentSelector.isEmpty()) {
            return "";
        }

        if (isJsonPath(contentSelector) && looksLikeJson(html)) {
            return getContentJson(html, rule);
        }

        Document doc = Jsoup.parse(html, baseUrl);
        StringBuilder content = new StringBuilder();

        Elements contentElements = doc.select(convertLegadoSelector(contentSelector));
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

    private String getContentJson(String json, ContentRule rule) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String contentSelector = cleanLegadoSelector(rule.getContent());
            JsonNode contentNode = resolveJsonPathWithFallback(root, contentSelector);

            if (contentNode == null) {
                return "";
            }

            String content = contentNode.isArray() ? contentNode.toString() : contentNode.asText("");

            if (rule.getReplaceRegex() != null && !rule.getReplaceRegex().isEmpty()) {
                String replaceTo = rule.getReplaceTo() != null ? rule.getReplaceTo() : "";
                content = content.replaceAll(rule.getReplaceRegex(), replaceTo);
            }

            if (rule.getFilter() != null && !rule.getFilter().isEmpty()) {
                content = content.replaceAll(rule.getFilter(), "");
            }

            return content;
        } catch (Exception e) {
            log.debug("JSON内容解析失败: {}", e.getMessage());
            return "";
        }
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
                return switch (parsed.attrName) {
                    case "text" -> element.text();
                    case "html" -> element.html();
                    default -> element.attr(parsed.attrName);
                };
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
                url = switch (parsed.attrName) {
                    case "text" -> element.text();
                    case "html" -> element.html();
                    default -> element.attr(parsed.attrName);
                };
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
                return switch (parsed.attrName) {
                    case "text" -> element.text();
                    case "html" -> element.html();
                    default -> element.attr(parsed.attrName);
                };
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
                url = switch (parsed.attrName) {
                    case "text" -> element.text();
                    case "html" -> element.html();
                    default -> element.attr(parsed.attrName);
                };
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

    private String cleanLegadoSelector(String selector) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }

        String result = selector;

        result = JS_BLOCK_PATTERN.matcher(result).replaceAll("");

        result = result.replaceAll("^\\s*@js\\s*:.*", "");

        result = result.replaceAll(",\\s*@js\\s*:.*$", "");

        result = result.trim();

        if (result.isEmpty()) {
            log.debug("清理后选择器为空: {}", selector);
        }

        return result;
    }

    private ParsedSelector parseSelector(String selector) {
        String cleaned = cleanLegadoSelector(selector);
        if (cleaned.isEmpty()) {
            return new ParsedSelector("", null, null);
        }

        String attrName = null;
        String cssSelector = cleaned;

        int atIndex = cleaned.lastIndexOf('@');
        if (atIndex > 0) {
            cssSelector = cleaned.substring(0, atIndex);
            String attrPart = cleaned.substring(atIndex);
            Matcher attrMatcher = ATTR_PATTERN.matcher(attrPart);
            if (attrMatcher.matches()) {
                attrName = attrMatcher.group(1);
            }
        }

        String converted = convertLegadoSelector(cssSelector);
        Matcher matcher = CSS_SELECTOR_PATTERN.matcher(converted);
        if (matcher.matches()) {
            String mainSelector = matcher.group(1);
            String subSelector = matcher.group(3);
            return new ParsedSelector(mainSelector, attrName, subSelector);
        }

        return new ParsedSelector(converted, attrName, null);
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
