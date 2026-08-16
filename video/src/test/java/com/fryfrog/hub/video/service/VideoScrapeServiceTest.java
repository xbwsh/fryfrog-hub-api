package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoScrapeServiceTest {

    @Mock private VideoRepository repository;
    @Mock private TmdbService tmdbService;
    @Mock private NfoService nfoService;
    @Mock private SeriesService seriesService;
    @Mock private VideoActorRepository actorRepository;
    @Mock private VideoAssetService assetService;
    @Mock private VideoOrganizeService organizeService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private SystemSettingService settingService;
    @Mock private ScrapeProgressService scrapeProgressService;
    @Mock private MediaLibraryService mediaLibraryService;
    @Mock private VideoScanService scanService;

    @InjectMocks
    private VideoScrapeService service;

    @Test
    void pickBestTmdbMatch_prefersAdultEntryWhenOriginalTitleMatches() {
        var normal = item(1L, "紫音", "紫音 ～穷途的魔法少女～", false, "/normal.jpg");
        var adult = item(2L, "紫音", "紫音 ～穷途的魔法少女～", true, null);

        var selected = service.pickBestTmdbMatch(List.of(normal, adult), "紫音");

        assertThat(selected).isSameAs(adult);
    }

    @Test
    void pickBestTmdbMatch_usesMetadataCompletenessWhenAdultStatusMatches() {
        var incomplete = item(1L, "紫音", "紫音 ～穷途的魔法少女～", true, null);
        var complete = item(2L, "紫音", "紫音 ～穷途的魔法少女～", true, "/complete.jpg");

        var selected = service.pickBestTmdbMatch(List.of(incomplete, complete), "紫音");

        assertThat(selected).isSameAs(complete);
    }

    @Test
    void pickBestTmdbMatch_fallsBackToNormalWhenNoAdultEntryExists() {
        var normal1 = item(1L, "紫音", "紫音 ～穷途的魔法少女～", false, "/normal1.jpg");
        var normal2 = item(2L, "紫音", "紫音 ～穷途的魔法少女～", false, "/normal2.jpg");

        var selected = service.pickBestTmdbMatch(List.of(normal1, normal2), "紫音");

        // 两个都非成人，应选元数据更完整的
        assertThat(selected).isSameAs(normal1);
        assertThat(selected.getBackdropPath()).isEqualTo("/normal1.jpg");
    }

    @Test
    void pickBestTmdbMatch_adultPreferredOverRicherMetadata() {
        // normal 有完整背景图，adult 没有 — 仍然优先选 adult
        var normal = item(1L, "紫音", "紫音 ～穷途的魔法少女～", false, "/backdrop.jpg");
        var adult = item(2L, "紫音", "紫音 ～穷途的魔法少女～", true, null);

        var selected = service.pickBestTmdbMatch(List.of(normal, adult), "紫音");

        assertThat(selected).isSameAs(adult);
    }

    @Test
    void pickBestTmdbMatch_fuzzyAdultPreferredOverFuzzyNormal() {
        // 非精确匹配，通过相似度评分匹配；同 originalTitle 时成人优先
        var normal = item(1L, "紫音 ～穷途的魔法少女～", "紫音 ～穷途的魔法少女～", false, "/backdrop.jpg");
        var adult = item(2L, "紫音 ～穷途的魔法少女～", "紫音 ～穷途的魔法少女～", true, "/backdrop.jpg");

        var selected = service.pickBestTmdbMatch(List.of(normal, adult), "紫音 ～穷途的魔法少女～");

        assertThat(selected).isSameAs(adult);
    }

    @Test
    void pickBestTmdbMatch_differentOriginalTitles_notAdultPriority() {
        // 不同 originalTitle 的条目之间不做成人优先
        var a = item(1L, "紫音", "紫音", false, "/backdrop.jpg");
        var b = item(2L, "紫音 adult", "紫音 adult", true, "/backdrop2.jpg");

        var selected = service.pickBestTmdbMatch(List.of(a, b), "紫音");

        // a 的 originalTitle 更接近查询，应选 a
        assertThat(selected).isSameAs(a);
    }

    @Test
    void autoScrape_noTmdbResults_movesVideoToUnscrapedDir() {
        Video video = video("abc.mkv", "abc");

        when(settingService.getBoolean("scrape.auto-scrape", true)).thenReturn(true);
        when(scanService.findUnscraped(null)).thenReturn(List.of(video));
        when(nfoService.isInUnscrapedDir(video)).thenReturn(false);
        when(mediaLibraryService.getLibraryById(10L)).thenReturn(new com.fryfrog.hub.common.model.MediaLibrary());
        when(tmdbService.isConfigured()).thenReturn(true);
        when(tmdbService.searchMovies(anyString())).thenReturn(List.of());
        when(tmdbService.searchTv(anyString())).thenReturn(List.of());
        when(tmdbService.searchMovies(anyString(), any())).thenReturn(List.of());
        when(tmdbService.searchTv(anyString(), any())).thenReturn(List.of());
        when(repository.save(video)).thenReturn(video);
        when(repository.findAllById(any())).thenReturn(List.of());
        when(repository.findAll()).thenReturn(List.of());
        when(organizeService.moveToUnscrapedDir(video)).thenReturn(true);

        service.autoScrapeAll(false);

        verify(organizeService).moveToUnscrapedDir(video);
    }

    @Test
    void autoScrape_videoInUnscrapedDir_skippedWithoutTmdbCall() {
        Video video = video("abc.mkv", "abc");

        when(settingService.getBoolean("scrape.auto-scrape", true)).thenReturn(true);
        when(scanService.findUnscraped(null)).thenReturn(List.of(video));
        when(nfoService.isInUnscrapedDir(video)).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findAllById(any())).thenReturn(List.of());

        service.autoScrapeAll(false);

        verify(tmdbService, never()).searchMovies(anyString());
        verify(organizeService, never()).moveToUnscrapedDir(any());
    }

    private Video video(String fileName, String title) {
        Video video = new Video();
        video.setId(1L);
        video.setFileName(fileName);
        video.setTitle(title);
        video.setLibraryId(10L);
        video.setFilePath("D:/library/" + fileName);
        return video;
    }

    private TmdbSearchResult.TmdbSearchItem item(
            Long id, String title, String originalTitle, boolean adult, String backdropPath) {
        var item = new TmdbSearchResult.TmdbSearchItem();
        item.setId(id);
        item.setTitle(title);
        item.setOriginalTitle(originalTitle);
        item.setAdult(adult);
        item.setBackdropPath(backdropPath);
        return item;
    }
}
