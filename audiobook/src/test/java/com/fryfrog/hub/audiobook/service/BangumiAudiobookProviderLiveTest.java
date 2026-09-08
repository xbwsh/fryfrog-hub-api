package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bangumi 真实 API 冒烟测试：默认跳过（避免 CI 依赖外网），
 * 手动验证时设置环境变量 BGM_LIVE=1 运行（外网需自行配置 PROXY_HOST/PROXY_PORT）。
 */
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "BGM_LIVE", matches = "1")
class BangumiAudiobookProviderLiveTest {

    private static org.springframework.web.client.RestTemplate restTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(30000);
        String proxyHost = System.getenv("PROXY_HOST");
        String proxyPort = System.getenv("PROXY_PORT");
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort != null) {
            factory.setProxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))));
        }
        return new org.springframework.web.client.RestTemplate(factory);
    }

    private final BangumiAudiobookProvider provider =
            new BangumiAudiobookProvider(new com.fryfrog.hub.common.service.BangumiClient(
                    restTemplate(), "https://api.bgm.tv"));

    @Test
    void liveSearchAndFetch() throws Exception {
        List<AudiobookScrapeResult> results = provider.search("魔道祖师 有声书");
        assertThat(results).isNotEmpty();

        AudiobookScrapeResult first = results.get(0);
        System.out.println("[LIVE] 搜索: " + first.getTitle() + " / " + first.getAuthor()
                + " / " + first.getNarrator() + " / id=" + first.getSourceId()
                + " / 评分=" + first.getRating() + " / 年份=" + first.getYear());

        AudiobookScrapeResult detail = provider.fetch(first.getSourceId());
        assertThat(detail).isNotNull();
        assertThat(detail.getTitle()).isNotBlank();
        System.out.println("[LIVE] 详情: " + detail.getTitle()
                + " / 作者:" + detail.getAuthor()
                + " / 主演:" + detail.getNarrator()
                + " / 封面:" + detail.getCoverUrl());
    }
}
