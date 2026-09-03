package com.fryfrog.hub.video.service;

import com.fryfrog.hub.mediacore.service.MediaProbeService;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoScanServiceParseTest {

    @Mock private VideoRepository repository;
    @Mock private VideoActorRepository actorRepository;
    @Mock private SeriesService seriesService;
    @Mock private NfoService nfoService;
    @Mock private ScrapeProgressService progressService;
    @Mock private MediaLibraryService mediaLibraryService;
    @Mock private MediaProbeService probeService;

    @InjectMocks
    private VideoScanService service;

    private Video videoOf(String fileName) {
        Video video = new Video();
        video.setFileName(fileName);
        int[] se = service.parseSeasonEpisode(fileName);
        video.setSeasonNumber(se[0]);
        video.setEpisodeNumber(se[1]);
        return video;
    }

    @Test
    void isTvEpisode_detectsStandardPatterns() {
        assertThat(service.isTvEpisode(videoOf("Show.S01E02.mkv"))).isTrue();
        assertThat(service.isTvEpisode(videoOf("Show Season 1 Episode 3.mp4")))
                .as("Season/Episode 文字模式")
                .isTrue();
        assertThat(service.isTvEpisode(videoOf("Show.E05.mkv"))).isTrue();
        assertThat(service.isTvEpisode(videoOf("Show EP05.mkv"))).isTrue();
        assertThat(service.isTvEpisode(videoOf("Show #12.mkv"))).isTrue();
        assertThat(service.isTvEpisode(videoOf("某剧12.mkv"))).as("中文紧邻尾数字").isTrue();
    }

    @Test
    void isTvEpisode_doesNotMisclassifyMoviesWithTrailingNumbers() {
        // 回归：《Deadpool 2》《Se7en》这类尾部数字/字母数字电影曾被误判为 tv，
        // 导致自动刮削被强制走 tv 搜索
        assertThat(service.isTvEpisode(videoOf("Deadpool 2.mkv"))).isFalse();
        assertThat(service.isTvEpisode(videoOf("Se7en.1995.1080p.mkv"))).isFalse();
        assertThat(service.isTvEpisode(videoOf("Movie - 2.mkv"))).isFalse();
        assertThat(service.isTvEpisode(videoOf("Just A Movie.mkv"))).isFalse();
    }

    @Test
    void parseSeasonEpisode_extractsFromStrongPatterns() {
        assertThat(service.parseSeasonEpisode("Show.S02E07.mkv")).containsExactly(2, 7);
        assertThat(service.parseSeasonEpisode("Show.E05.mkv")).containsExactly(1, 5);
        assertThat(service.parseSeasonEpisode("Show #12.mkv")).containsExactly(1, 12);
        assertThat(service.parseSeasonEpisode("某剧12.mkv")).containsExactly(1, 12);
        // 弱模式仅作集数兜底，不再驱动 tv 判定
        assertThat(service.parseSeasonEpisode("Deadpool 2.mkv")).containsExactly(1, 2);
    }

    @Test
    void parseSeasonEpisode_epPatternRequiresSeparator() {
        // "Se7en" 中的 "e7" 不再被当作集数
        assertThat(service.parseSeasonEpisode("Se7en.mkv")).containsExactly(1, 1);
        assertThat(service.parseSeasonEpisode("Re2Cut.mp4")).containsExactly(1, 1);
    }
}
