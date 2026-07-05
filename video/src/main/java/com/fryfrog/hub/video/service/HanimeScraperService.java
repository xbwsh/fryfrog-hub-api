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

    public HanimeScraperService(CfBypassClient cfClient, SystemSettingService settingService) {
        this.cfClient = cfClient;
        this.settingService = settingService;
    }

    @PostConstruct
    public void init() {
        int ttl = settingService.getInteger("hub.hanime.scraper.cache-ttl", 60);
        int maxSize = settingService.getInteger("hub.hanime.scraper.cache-max-size", 1000);
        this.metadataCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl, TimeUnit.MINUTES)
                .build();
        log.info("Hanime scraper initialized - cache TTL: {}min, max size: {}", ttl, maxSize);
    }

    public String getBaseUrl() {
        return settingService.getValue("hub.hanime.base-url", "https://hanime1.me");
    }

    public long getRequestInterval() {
        return settingService.getInteger("hub.hanime.scraper.request-interval", 1500);
    }

    /**
     * 根据视频 ID 刮削元数据
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
