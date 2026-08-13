package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoServiceLibraryFilterTest {

    @Mock
    private VideoRepository repository;
    @Mock
    private VideoSeriesRepository seriesRepository;
    @Mock
    private VideoScanService scanService;
    @Mock
    private VideoScrapeService scrapeService;
    @Mock
    private VideoOrganizeService organizeService;
    @Mock
    private VideoPipelineService pipelineService;
    @Mock
    private MediaLibraryService mediaLibraryService;
    @Mock
    private NfoService nfoService;
    @Mock
    private CoverArtService coverArtService;
    @Mock
    private VideoAssetService assetService;
    @Mock
    private com.fryfrog.hub.common.service.ScrapeProgressService progressService;
    @Mock
    private FavoriteService favoriteService;
    @Mock
    private org.springframework.web.client.RestTemplate scraperRestTemplate;

    @InjectMocks
    private VideoService service;

    @Test
    void getAllVideos_restrictedUserUsesAllowedOnlyQuery() {
        when(mediaLibraryService.isRestrictedCurrentUser()).thenReturn(true);
        when(mediaLibraryService.getAllowableLibraryIds()).thenReturn(List.of(2L));
        Video v = new Video();
        v.setLibraryId(2L);
        when(repository.findAllByAllowedLibraries(List.of(2L))).thenReturn(List.of(v));

        List<Video> result = service.getAllVideos();

        assertThat(result).containsExactly(v);
        verify(repository, never()).findAllByEnabledLibraries(List.of(0L));
    }

    @Test
    void getAllVideos_adminUsesEnabledQuery() {
        when(mediaLibraryService.isRestrictedCurrentUser()).thenReturn(false);
        when(mediaLibraryService.getEnabledLibraryIds()).thenReturn(List.of(1L, 2L));
        Video v = new Video();
        v.setLibraryId(null);
        when(repository.findAllByEnabledLibraries(List.of(1L, 2L))).thenReturn(List.of(v));

        List<Video> result = service.getAllVideos();

        assertThat(result).containsExactly(v);
        verify(repository, never()).findAllByAllowedLibraries(List.of(1L, 2L));
    }
}