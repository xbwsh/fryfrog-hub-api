package com.fryfrog.hub.ebook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.ebook.dto.OnlineBookDTO;
import com.fryfrog.hub.ebook.dto.OnlineChapterDTO;
import com.fryfrog.hub.ebook.model.BookSource;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.SourceType;
import com.fryfrog.hub.ebook.model.rule.BookSourceRule;
import com.fryfrog.hub.ebook.parser.BookSourceRuleParser;
import com.fryfrog.hub.ebook.repository.BookSourceRepository;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineBookService {

    private final BookSourceRepository bookSourceRepository;
    private final EbookRepository ebookRepository;
    private final BookSourceRuleParser ruleParser;
    private final ContentCleaner contentCleaner;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Transactional(readOnly = true)
    public List<OnlineBookDTO> search(String keyword, Long sourceId) {
        List<BookSource> sources;
        if (sourceId != null) {
            BookSource source = bookSourceRepository.findById(sourceId).orElse(null);
            sources = source != null ? List.of(source) : List.of();
        } else {
            sources = bookSourceRepository.findByEnabledTrueAndDeletedFalseOrderBySortOrderAsc();
        }

        List<OnlineBookDTO> allResults = new ArrayList<>();

        for (BookSource source : sources) {
            try {
                List<OnlineBookDTO> results = searchFromSource(keyword, source);
                allResults.addAll(results);
            } catch (Exception e) {
                log.warn("从书源 {} 搜索失败: {}", source.getName(), e.getMessage());
            }
        }

        return allResults;
    }

    private List<OnlineBookDTO> searchFromSource(String keyword, BookSource source) {
        try {
            BookSourceRule rule = ruleParser.parse(source.getRuleJson());
            String searchUrl = buildSearchUrl(rule.getSearchUrl(), keyword, source.getUrl());

            String html = fetchUrl(searchUrl, source.getHeaderJson());
            List<Map<String, String>> searchResults = ruleParser.search(html, rule.getRuleSearch(), source.getUrl());

            return searchResults.stream()
                    .map(result -> OnlineBookDTO.builder()
                            .name(result.getOrDefault("name", ""))
                            .author(result.getOrDefault("author", ""))
                            .coverUrl(resolveUrl(result.getOrDefault("coverUrl", ""), source.getUrl()))
                            .bookUrl(resolveUrl(result.getOrDefault("bookUrl", ""), source.getUrl()))
                            .intro(result.getOrDefault("intro", ""))
                            .kind(result.getOrDefault("kind", ""))
                            .lastChapter(result.getOrDefault("lastChapter", ""))
                            .wordCount(result.getOrDefault("wordCount", ""))
                            .sourceId(source.getId())
                            .sourceName(source.getName())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("搜索书源 {} 失败", source.getName(), e);
            return List.of();
        }
    }

    private String buildSearchUrl(String searchUrlTemplate, String keyword, String baseUrl) {
        if (searchUrlTemplate == null || searchUrlTemplate.isEmpty()) {
            return "";
        }

        String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = searchUrlTemplate.replace("{{key}}", encodedKeyword);
        url = url.replace("{{key-utf8}}", encodedKeyword);

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        return resolveUrl(url, baseUrl);
    }

    @Transactional(readOnly = true)
    public List<OnlineChapterDTO> getChapters(String bookUrl, Long sourceId) {
        BookSource source = bookSourceRepository.findById(sourceId)
                .orElseThrow(() -> new RuntimeException("书源不存在: " + sourceId));

        try {
            BookSourceRule rule = ruleParser.parse(source.getRuleJson());
            String html = fetchUrl(bookUrl, source.getHeaderJson());

            if (rule.getRuleBookInfo() != null && rule.getRuleBookInfo().getTocUrl() != null) {
                Map<String, String> bookInfo = ruleParser.getBookInfo(html, rule.getRuleBookInfo(), source.getUrl());
                String tocUrl = bookInfo.get("tocUrl");
                if (tocUrl != null && !tocUrl.isEmpty()) {
                    html = fetchUrl(tocUrl, source.getHeaderJson());
                }
            }

            List<Map<String, String>> chapters = ruleParser.getChapterList(html, rule.getRuleToc(), source.getUrl());

            int[] index = {0};
            return chapters.stream()
                    .map(chapter -> OnlineChapterDTO.builder()
                            .chapterNum(++index[0])
                            .chapterName(chapter.getOrDefault("chapterName", ""))
                            .chapterUrl(resolveUrl(chapter.getOrDefault("chapterUrl", ""), source.getUrl()))
                            .cached(false)
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("获取章节目录失败: {}", bookUrl, e);
            return List.of();
        }
    }

    @Transactional
    public String getChapterContent(String chapterUrl, Long sourceId) {
        BookSource source = bookSourceRepository.findById(sourceId)
                .orElseThrow(() -> new RuntimeException("书源不存在: " + sourceId));

        try {
            BookSourceRule rule = ruleParser.parse(source.getRuleJson());
            StringBuilder fullContent = new StringBuilder();
            String currentUrl = chapterUrl;

            while (currentUrl != null && !currentUrl.isEmpty()) {
                String html = fetchUrl(currentUrl, source.getHeaderJson());
                String content = ruleParser.getContent(html, rule.getRuleContent(), source.getUrl());

                if (source.getCleanRuleJson() != null && !source.getCleanRuleJson().isEmpty()) {
                    content = contentCleaner.clean(content, source.getCleanRuleJson());
                } else {
                    content = contentCleaner.clean(content, null);
                }

                fullContent.append(content).append("\n\n");

                currentUrl = ruleParser.getNextContentUrl(html, rule.getRuleContent(), source.getUrl());
                if (currentUrl != null) {
                    currentUrl = resolveUrl(currentUrl, source.getUrl());
                }
            }

            return fullContent.toString().trim();
        } catch (Exception e) {
            log.error("获取章节内容失败: {}", chapterUrl, e);
            return "获取章节内容失败: " + e.getMessage();
        }
    }

    @Transactional
    public Ebook addToShelf(String bookUrl, Long sourceId, Map<String, String> bookInfo) {
        if (ebookRepository.existsByOnlineUrlAndSourceType(bookUrl, SourceType.ONLINE)) {
            throw new IllegalArgumentException("书籍已在书架中");
        }

        BookSource source = bookSourceRepository.findById(sourceId)
                .orElseThrow(() -> new RuntimeException("书源不存在: " + sourceId));

        if (bookInfo == null) {
            bookInfo = fetchBookInfo(bookUrl, source);
        }

        Ebook ebook = new Ebook();
        ebook.setTitle(bookInfo.getOrDefault("name", "未知书名"));
        ebook.setAuthor(bookInfo.getOrDefault("author", "未知作者"));
        ebook.setDescription(bookInfo.getOrDefault("intro", ""));
        ebook.setSourceType(SourceType.ONLINE);
        ebook.setBookSourceId(sourceId);
        ebook.setOnlineUrl(bookUrl);
        ebook.setFavorite(false);

        Ebook saved = ebookRepository.save(ebook);
        log.info("将在线书籍加入书架: {} ({})", saved.getTitle(), bookUrl);
        return saved;
    }

    private Map<String, String> fetchBookInfo(String bookUrl, BookSource source) {
        try {
            BookSourceRule rule = ruleParser.parse(source.getRuleJson());
            String html = fetchUrl(bookUrl, source.getHeaderJson());

            if (rule.getRuleBookInfo() != null) {
                return ruleParser.getBookInfo(html, rule.getRuleBookInfo(), source.getUrl());
            }

            Map<String, String> info = new HashMap<>();
            info.put("name", "");
            info.put("author", "");
            info.put("intro", "");
            return info;
        } catch (Exception e) {
            log.error("获取书籍信息失败: {}", bookUrl, e);
            Map<String, String> info = new HashMap<>();
            info.put("name", "");
            info.put("author", "");
            info.put("intro", "");
            return info;
        }
    }

    private String fetchUrl(String url, String headerJson) {
        try {
            return webClientBuilder.build()
                    .get()
                    .uri(url)
                    .headers(headers -> {
                        if (headerJson != null && !headerJson.isEmpty()) {
                            try {
                                Map<String, String> headerMap = objectMapper.readValue(headerJson, Map.class);
                                headerMap.forEach(headers::add);
                            } catch (Exception e) {
                                log.debug("解析请求头失败: {}", e.getMessage());
                            }
                        }
                        headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("获取URL失败: {}", url, e);
            return "";
        }
    }

    private String resolveUrl(String url, String baseUrl) {
        if (url == null || url.isEmpty()) {
            return "";
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        try {
            java.net.URI baseUri = new java.net.URI(baseUrl);
            java.net.URI resolved = baseUri.resolve(url);
            return resolved.toString();
        } catch (Exception e) {
            log.debug("解析URL失败: url={}, baseUrl={}", url, baseUrl);
            return url;
        }
    }
}
