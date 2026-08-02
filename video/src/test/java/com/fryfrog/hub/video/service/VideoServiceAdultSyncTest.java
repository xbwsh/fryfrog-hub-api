package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoServiceAdultSyncTest {

    @Mock private VideoRepository repository;
    @Mock private VideoSeriesRepository seriesRepository;
    @Mock private VideoScanService scanService;
    @Mock private VideoScrapeService scrapeService;
    @Mock private VideoOrganizeService organizeService;
    @Mock private VideoAssetService assetService;
    @Mock private VideoPipelineService pipelineService;
    @Mock private HanimeScraperService hanimeScraperService;
    @Mock private MediaLibraryService mediaLibraryService;
    @Mock private NfoService nfoService;
    @Mock private CoverArtService coverArtService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RestTemplate scraperRestTemplate;

    @InjectMocks
    private VideoService service;

    @Test
    void syncAdultByLibrary_marksAllVideosAndSeriesWhenEnabled() {
        VideoSeries series = new VideoSeries();
        series.setId(10L);
        series.setTitle("测试系列");

        Video standalone = new Video();
        standalone.setId(1L);
        standalone.setTitle("独立视频");
        standalone.setIsAdult(false);

        Video inSeries = new Video();
        inSeries.setId(2L);
        inSeries.setTitle("系列内视频");
        inSeries.setIsAdult(null);
        inSeries.setSeries(series);

        when(repository.findByLibraryId(5L)).thenReturn(List.of(standalone, inSeries));

        int updated = service.syncAdultByLibrary(5L, true);

        assertThat(updated).isEqualTo(3); // 2 个视频 + 1 个系列
        assertThat(standalone.getIsAdult()).isTrue();
        assertThat(inSeries.getIsAdult()).isTrue();
        assertThat(series.getIsAdult()).isTrue();
        verify(repository).saveAll(anyList());
        verify(seriesRepository).save(series);
    }

    @Test
    void syncAdultByLibrary_clearsFlagsWhenDisabled() {
        VideoSeries series = new VideoSeries();
        series.setId(10L);
        series.setTitle("测试系列");
        series.setIsAdult(true);

        Video video = new Video();
        video.setId(1L);
        video.setTitle("成人视频");
        video.setIsAdult(true);
        video.setSeries(series);

        when(repository.findByLibraryId(5L)).thenReturn(List.of(video));

        int updated = service.syncAdultByLibrary(5L, false);

        assertThat(updated).isEqualTo(2); // 1 个视频 + 1 个系列
        assertThat(video.getIsAdult()).isFalse();
        assertThat(series.getIsAdult()).isFalse();
        verify(repository).saveAll(anyList());
        verify(seriesRepository).save(series);
    }

    @Test
    void syncAdultByLibrary_noChangesDoesNotWrite() {
        Video video = new Video();
        video.setId(1L);
        video.setTitle("已是成人");
        video.setIsAdult(true);

        when(repository.findByLibraryId(5L)).thenReturn(List.of(video));

        int updated = service.syncAdultByLibrary(5L, true);

        assertThat(updated).isZero();
        verify(repository, never()).saveAll(anyList());
        verify(seriesRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void syncAdultByLibrary_emptyLibraryReturnsZero() {
        when(repository.findByLibraryId(9L)).thenReturn(List.of());

        int updated = service.syncAdultByLibrary(9L, true);

        assertThat(updated).isZero();
        verify(repository, never()).saveAll(anyList());
    }
}
