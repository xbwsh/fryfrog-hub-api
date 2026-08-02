package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.video.dto.HanimeMetadata;
import com.fryfrog.hub.video.util.ChineseConverter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hanime 元数据刮削服务
 * <p>
 * 从 hanime1.me 网站抓取视频元数据，使用 Jsoup 解析 HTML
 */
@Service
@Slf4j
public class HanimeScraperService {

    private final CfBypassClient cfClient;
    private final SystemSettingService settingService;
    private Cache<String, HanimeMetadata> metadataCache;

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final Pattern VIDEO_URL_PATTERN = Pattern.compile("https?://[^\"'\\s>]+\\.(?:mp4|m3u8)[^\"'\\s>]*");
    private static final Pattern JS_SOURCE_PATTERN = Pattern.compile("const\\s+source\\s*=\\s*['\"]([^'\"]+)['\"]");

    public HanimeScraperService(CfBypassClient cfClient, SystemSettingService settingService) {
        this.cfClient = cfClient;
        this.settingService = settingService;
    }

    @PostConstruct
    public void init() {
        int ttl = settingService.getInteger("hanime.scraper.cache-ttl", 60);
        int maxSize = settingService.getInteger("hanime.scraper.cache-max-size", 1000);
        this.metadataCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl, TimeUnit.MINUTES)
                .build();
        log.info("Hanime scraper initialized - cache TTL: {}min, max size: {}", ttl, maxSize);
    }

    public String getBaseUrl() {
        return settingService.getValue("hanime.base-url", "https://hanime1.me");
    }

    public long getRequestInterval() {
        return settingService.getInteger("hanime.scraper.request-interval", 1500);
    }

    /**
     * 根据视频 ID 刮削元数据（不含视频播放地址）
     */
    public HanimeMetadata scrape(String videoId) {
        HanimeMetadata cached = metadataCache.getIfPresent(videoId);
        if (cached != null) {
            log.debug("Hanime metadata from cache: {}", videoId);
            return cached;
        }

        log.info("Scraping Hanime metadata: {}", videoId);
        sleep(getRequestInterval());

        String url = getBaseUrl() + "/watch?v=" + videoId;
        String html = cfClient.fetch(url, null);

        if (html == null || html.isEmpty()) {
            log.error("Failed to fetch Hanime page: {}", videoId);
            return null;
        }

        Document doc = Jsoup.parse(html);
        HanimeMetadata metadata = extractMetadata(doc, videoId);

        if (metadata != null) {
            metadataCache.put(videoId, metadata);
            log.info("Hanime scrape complete: {} - {}", videoId, metadata.getTitle());
        }

        return metadata;
    }

    /**
     * 解析视频完整信息（含视频播放地址）
     */
    public HanimeMetadata scrapeWithSources(String videoId) {
        HanimeMetadata cached = metadataCache.getIfPresent("sources_" + videoId);
        if (cached != null && cached.getSources() != null && !cached.getSources().isEmpty()) {
            log.debug("Hanime metadata with sources from cache: {}", videoId);
            return cached;
        }

        log.info("Scraping Hanime with sources: {}", videoId);
        sleep(getRequestInterval());

        String watchUrl = getBaseUrl() + "/watch?v=" + videoId;
        String downloadUrl = getBaseUrl() + "/download?v=" + videoId;

        String watchHtml = cfClient.fetch(watchUrl, null);
        if (watchHtml == null || watchHtml.isEmpty()) {
            log.error("Failed to fetch Hanime watch page: {}", videoId);
            return null;
        }

        Document watchDoc = Jsoup.parse(watchHtml);
        HanimeMetadata metadata = extractMetadata(watchDoc, videoId);

        if (metadata == null) {
            return null;
        }

        metadata.setWatchUrl(watchUrl);

        // 从 watch 页面提取视频源
        List<HanimeMetadata.VideoSource> sources = extractVideoSourcesFromWatch(watchDoc);

        // 如果 watch 页面没有找到，尝试 download 页面
        if (sources.isEmpty()) {
            log.debug("No sources from watch page, trying download page: {}", videoId);
            sleep(getRequestInterval());
            String downloadHtml = cfClient.fetch(downloadUrl, null);
            if (downloadHtml != null && !downloadHtml.isEmpty()) {
                Document downloadDoc = Jsoup.parse(downloadHtml);
                sources = extractVideoSourcesFromDownload(downloadDoc);
            }
        }

        // 如果还没有找到，从 watch 页面的 JS 中提取
        if (sources.isEmpty()) {
            log.debug("Trying to extract from JS: {}", videoId);
            sources = extractVideoSourcesFromJs(watchHtml);
        }

        metadata.setSources(sources);

        // 设置默认播放地址（最高画质）
        if (!sources.isEmpty()) {
            metadata.setDefaultUrl(sources.get(0).getUrl());
        }

        // 缓存带源的结果
        metadataCache.put("sources_" + videoId, metadata);
        log.info("Hanime scrape with sources complete: {} - {} sources found", videoId, sources.size());

        return metadata;
    }

    /**
     * 从 watch 页面的 video 标签提取视频源
     */
    private List<HanimeMetadata.VideoSource> extractVideoSourcesFromWatch(Document doc) {
        List<HanimeMetadata.VideoSource> sources = new ArrayList<>();

        Elements videoSources = doc.select("video#player source");
        for (Element source : videoSources) {
            String url = source.attr("src");
            String resolution = source.attr("size");
            String type = source.attr("type");

            if (url != null && !url.isEmpty()) {
                String format = "mp4";
                if (type != null && type.contains("mpegurl")) {
                    format = "m3u8";
                } else if (url.contains(".m3u8")) {
                    format = "m3u8";
                }

                sources.add(HanimeMetadata.VideoSource.builder()
                        .resolution(resolution != null && !resolution.isEmpty() ? resolution + "p" : "unknown")
                        .format(format)
                        .url(url)
                        .build());
            }
        }

        // 按分辨率排序（高到低）
        sources.sort((a, b) -> {
            int aRes = parseResolution(a.getResolution());
            int bRes = parseResolution(b.getResolution());
            return Integer.compare(bRes, aRes);
        });

        return sources;
    }

    /**
     * 从 download 页面的 download-table 提取视频源
     */
    private List<HanimeMetadata.VideoSource> extractVideoSourcesFromDownload(Document doc) {
        List<HanimeMetadata.VideoSource> sources = new ArrayList<>();

        Elements rows = doc.select("table.download-table tr");
        for (int i = 1; i < rows.size(); i++) { // 跳过表头
            Element row = rows.get(i);
            Elements tds = row.select("td");

            if (tds.size() >= 4) {
                String resolution = tds.get(1).text().trim();
                String format = tds.get(2).text().trim();
                String fileSize = tds.get(3).text().trim();
                Element link = row.select("a[data-url]").first();

                if (link != null) {
                    String url = link.attr("data-url");
                    if (url != null && !url.isEmpty()) {
                        sources.add(HanimeMetadata.VideoSource.builder()
                                .resolution(resolution)
                                .format(format.toLowerCase())
                                .url(url)
                                .fileSize(parseFileSize(fileSize))
                                .build());
                    }
                }
            }
        }

        return sources;
    }

    /**
     * 从 watch 页面的 JavaScript 中提取视频源
     */
    private List<HanimeMetadata.VideoSource> extractVideoSourcesFromJs(String html) {
        List<HanimeMetadata.VideoSource> sources = new ArrayList<>();

        // 尝试匹配 const source = '...'
        Matcher jsMatcher = JS_SOURCE_PATTERN.matcher(html);
        if (jsMatcher.find()) {
            String url = jsMatcher.group(1);
            if (url != null && !url.isEmpty()) {
                String format = url.contains(".m3u8") ? "m3u8" : "mp4";
                sources.add(HanimeMetadata.VideoSource.builder()
                        .resolution("default")
                        .format(format)
                        .url(url)
                        .build());
            }
        }

        // 尝试匹配所有 mp4/m3u8 URL
        if (sources.isEmpty()) {
            Matcher urlMatcher = VIDEO_URL_PATTERN.matcher(html);
            Set<String> seen = new HashSet<>();
            while (urlMatcher.find()) {
                String url = urlMatcher.group();
                if (seen.add(url)) {
                    String format = url.contains(".m3u8") ? "m3u8" : "mp4";
                    sources.add(HanimeMetadata.VideoSource.builder()
                            .resolution("default")
                            .format(format)
                            .url(url)
                            .build());
                }
            }
        }

        return sources;
    }

    private int parseResolution(String resolution) {
        if (resolution == null) return 0;
        String num = resolution.replaceAll("[^\\d]", "");
        if (num.isEmpty()) return 0;
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseFileSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isEmpty()) return 0;
        try {
            String numStr = sizeStr.replaceAll("[^\\d.]", "");
            double num = Double.parseDouble(numStr);
            if (sizeStr.contains("GB")) return (long) (num * 1024 * 1024 * 1024);
            if (sizeStr.contains("MB")) return (long) (num * 1024 * 1024);
            if (sizeStr.contains("KB")) return (long) (num * 1024);
            return (long) num;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 批量刮削
     */
    public Map<String, HanimeMetadata> scrapeBatch(List<String> videoIds) {
        Map<String, HanimeMetadata> results = new LinkedHashMap<>();
        for (String videoId : videoIds) {
            try {
                HanimeMetadata metadata = scrape(videoId);
                if (metadata != null) {
                    results.put(videoId, metadata);
                }
            } catch (Exception e) {
                log.error("Hanime scrape failed: {} - {}", videoId, e.getMessage());
            }
        }
        return results;
    }

    private HanimeMetadata extractMetadata(Document doc, String videoId) {
        try {
            HanimeMetadata.HanimeMetadataBuilder builder = HanimeMetadata.builder()
                    .videoId(videoId)
                    .scrapedAt(System.currentTimeMillis());

            // 标题
            Element titleEl = doc.select("#shareBtn-title").first();
            if (titleEl != null) {
                builder.title(ChineseConverter.toSimplified(titleEl.text().trim()));
            }

            // 副标题、简介
            Elements descWrapper = doc.select(".video-description-panel");
            if (!descWrapper.isEmpty()) {
                Elements divs = descWrapper.select("div");
                if (divs.size() > 1) {
                    builder.subtitle(ChineseConverter.toSimplified(divs.get(1).text().trim()));
                }
                if (divs.size() > 2) {
                    builder.description(ChineseConverter.toSimplified(divs.get(2).text().trim()));
                }
            }

            // 封面 URL
            Element videoEl = doc.select("video#player").first();
            if (videoEl != null) {
                builder.coverUrl(videoEl.attr("poster"));
            }

            // 制作商
            Element studioEl = doc.select("#video-artist-name").first();
            if (studioEl != null) {
                builder.studio(ChineseConverter.toSimplified(studioEl.text().trim()));
            }

            // 视频类型
            Element typeEl = doc.select("#video-artist-name ~ a").first();
            if (typeEl != null) {
                builder.videoType(ChineseConverter.toSimplified(typeEl.text().trim()));
            }

            // 观看次数和上传日期
            if (!descWrapper.isEmpty()) {
                Element viewsEl = descWrapper.select("div:first-child").first();
                if (viewsEl != null) {
                    String viewsText = viewsEl.text();
                    Matcher dateMatcher = DATE_PATTERN.matcher(viewsText);
                    if (dateMatcher.find()) {
                        builder.uploadDate(dateMatcher.group(1));
                    }
                    builder.viewCount(parseViewCount(viewsText));
                }
            }

            // 标签
            List<String> tags = extractTags(doc);
            builder.tags(tags);

            return builder.build();

        } catch (Exception e) {
            log.error("Failed to extract Hanime metadata: {} - {}", videoId, e.getMessage(), e);
            return null;
        }
    }

    private List<String> extractTags(Document doc) {
        List<String> tags = new ArrayList<>();
        Elements tagElements = doc.select(".single-video-tag a[href*=tags]");
        for (Element tagEl : tagElements) {
            String tagText = tagEl.text().trim().replaceAll("\\s*\\(\\d+\\)$", "");
            if (!tagText.isEmpty()) {
                tags.add(ChineseConverter.toSimplified(tagText));
            }
        }
        return tags;
    }

    private int parseViewCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            String numPart = text;
            int multiplier = 1;
            if (text.contains("萬")) {
                numPart = text.split("萬")[0];
                multiplier = 10000;
            } else if (text.contains("千")) {
                numPart = text.split("千")[0];
                multiplier = 1000;
            }
            String numStr = numPart.replaceAll("[^\\d.]", "");
            if (numStr.isEmpty()) return 0;
            return (int) (Double.parseDouble(numStr) * multiplier);
        } catch (Exception e) {
            return 0;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
