package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.dto.AudiobookScrapeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 悦听吧真实站点冒烟测试：默认跳过（避免 CI 依赖外网），
 * 手动验证时设置环境变量 YTB_LIVE=1 运行。
 */
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "YTB_LIVE", matches = "1")
class YuetingbaAudiobookProviderLiveTest {

    private final YuetingbaAudiobookProvider provider =
            new YuetingbaAudiobookProvider("http://yuetingba.cn");

    @Test
    void liveSearchAndFetch() throws Exception {
        List<AudiobookScrapeResult> results = provider.search("剑来");
        assertThat(results).isNotEmpty();

        AudiobookScrapeResult first = results.get(0);
        System.out.println("[LIVE] 搜索: " + first.getTitle() + " / " + first.getAuthor()
                + " / " + first.getNarrator() + " / id=" + first.getSourceId());

        AudiobookScrapeResult detail = provider.fetch(first.getSourceId());
        assertThat(detail).isNotNull();
        assertThat(detail.getTitle()).isNotBlank();
        System.out.println("[LIVE] 详情: " + detail.getTitle()
                + " / " + detail.getAuthor()
                + " / 演播:" + detail.getNarrator()
                + " / 封面:" + detail.getCoverUrl());
        System.out.println("[LIVE] 简介前100字: "
                + (detail.getOverview() == null ? "null"
                : detail.getOverview().substring(0, Math.min(100, detail.getOverview().length()))));
    }
}
