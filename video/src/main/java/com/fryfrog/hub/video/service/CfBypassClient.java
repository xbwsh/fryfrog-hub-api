package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * Cloudflare Bypass 服务客户端
 * <p>
 * 通过调用 cf-bypass Docker 服务来绕过 Cloudflare 5s 盾
 * cf-bypass 服务: https://github.com/sarperavci/CloudflareBypassForScraping
 */
@Slf4j
@Component
public class CfBypassClient {

    private final RestTemplate restTemplate;
    private final SystemSettingService settingService;

    // CF 挑战页面特征码
    private static final String[] CF_CHALLENGE_MARKERS = {
            "Just a moment...",
            "challenges.cloudflare.com",
            "cf-browser-verification",
            "cf_chl_opt",
            "_cf_chl_tk"
    };

    public CfBypassClient(RestTemplate restTemplate, SystemSettingService settingService) {
        this.restTemplate = restTemplate;
        this.settingService = settingService;
    }

    public String getCfBypassUrl() {
        return settingService.getValue("hub.hanime.cf-bypass-url", "http://localhost:8000");
    }

    public boolean isUseProxy() {
        return settingService.getBoolean("hub.hanime.use-proxy", false);
    }

    public int getMaxRetries() {
        return settingService.getInteger("hub.hanime.scraper.max-retries", 3);
    }

    /**
     * 通过 CF Bypass 服务获取页面内容
     *
     * @param targetUrl 目标 URL
     * @param params    查询参数
     * @return HTML 内容
     */
    public String fetch(String targetUrl, Map<String, String> params) {
        return fetch(targetUrl, params, getMaxRetries());
    }

    /**
     * 通过 CF Bypass 服务获取页面内容（带重试）
     */
    public String fetch(String targetUrl, Map<String, String> params, int maxRetries) {
        URI targetUri = URI.create(targetUrl);
        String hostname = targetUri.getHost();
        String bypassUrl = buildBypassUrl(targetUrl);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                boolean forceRefresh = attempt > 1;

                HttpHeaders headers = new HttpHeaders();
                headers.set("x-hostname", hostname);

                if (forceRefresh) {
                    headers.set("x-bypass-cache", "true");
                }

                if (isUseProxy()) {
                    String proxyHost = settingService.getValue("hub.proxy.host", "127.0.0.1");
                    int proxyPort = settingService.getInteger("hub.proxy.port", 7890);
                    headers.set("x-proxy", "http://" + proxyHost + ":" + proxyPort);
                }

                String fullUrl = bypassUrl;
                if (params != null && !params.isEmpty()) {
                    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(bypassUrl);
                    params.forEach(builder::queryParam);
                    fullUrl = builder.toUriString();
                }

                log.debug("[CF] GET {} (attempt {}/{})", targetUrl, attempt, maxRetries);

                long startTime = System.currentTimeMillis();
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<String> response = restTemplate.exchange(
                        fullUrl, HttpMethod.GET, entity, String.class);
                long elapsed = System.currentTimeMillis() - startTime;

                log.debug("[CF] Response {}, {}ms", response.getStatusCode(), elapsed);

                if (response.getStatusCode().is5xxServerError()) {
                    log.warn("[CF] Bypass service returned {}, will retry", response.getStatusCode());
                    continue;
                }

                String content = response.getBody();
                if (isCfChallenge(content)) {
                    log.warn("[CF] CF challenge page detected, will force refresh");
                    continue;
                }

                return content;

            } catch (Exception e) {
                log.error("[CF] Request failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());
            }
        }

        log.error("[CF] Max retries ({}) reached for: {}", maxRetries, targetUrl);
        return "";
    }

    private boolean isCfChallenge(String content) {
        if (content == null || content.length() < 50) {
            return false;
        }
        String snippet = content.substring(0, Math.min(5000, content.length()));
        for (String marker : CF_CHALLENGE_MARKERS) {
            if (snippet.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String buildBypassUrl(String targetUrl) {
        URI targetUri = URI.create(targetUrl);
        String path = targetUri.getRawPath();
        if (targetUri.getRawQuery() != null) {
            path += "?" + targetUri.getRawQuery();
        }
        return getCfBypassUrl().replaceAll("/$", "") + path;
    }
}
