package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.video.dto.SeriesListDTO;
import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import com.fryfrog.hub.video.service.ActorProfileService;
import com.fryfrog.hub.video.service.FavoriteService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoControllerActorWorksTest {

    @Mock
    private VideoService service;
    @Mock
    private NfoService nfoService;
    @Mock
    private WatchProgressService watchProgressService;
    @Mock
    private VideoActorRepository actorRepository;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private VideoSeriesRepository seriesRepository;
    @Mock
    private FavoriteService favoriteService;
    @Mock
    private SeriesService seriesService;
    @Mock
    private ActorProfileService actorProfileService;
    @Mock
    private VideoControllerSupport support;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private VideoController controller;

    private Video video(long id, String title, long libraryId, VideoSeries series) {
        Video v = new Video();
        v.setId(id);
        v.setTitle(title);
        v.setLibraryId(libraryId);
        v.setSeries(series);
        return v;
    }

    private VideoActor actorLink(Video v) {
        VideoActor a = new VideoActor();
        a.setVideo(v);
        return a;
    }

    @Test
    void actorNotFound_throws404() {
        when(actorRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getActorWorks(1L, 0, 20, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aggregatesSeriesIntoOneItem_andKeepsStandalone() {
        VideoActor actor = new VideoActor();
        actor.setId(5L);
        actor.setName("吴京");
        actor.setSourceActorId(1001L);
        when(actorRepository.findById(5L)).thenReturn(Optional.of(actor));

        VideoSeries seriesA = new VideoSeries();
        seriesA.setId(900L);
        seriesA.setTitle("流浪地球系列");
        seriesA.setYear(2019);

        Video v10 = video(10L, "流浪地球 第1集", 1L, seriesA);
        v10.setYear(2019);
        Video v11 = video(11L, "流浪地球 第2集", 1L, seriesA);
        v11.setYear(2019);
        Video v12 = video(12L, "战狼", 1L, null);
        v12.setYear(2017);
        Video vHidden = video(13L, "隐藏库影片", 99L, null);
        vHidden.setYear(2016);

        when(actorRepository.findBySourceActorId(1001L))
                .thenReturn(List.of(actorLink(v10), actorLink(v11)));
        when(actorRepository.findByNameIgnoreCase("吴京"))
                .thenReturn(List.of(actorLink(v10), actorLink(v12), actorLink(vHidden)));
        when(support.isLibraryVisibleToCurrentUser(1L)).thenReturn(true);
        when(support.isLibraryVisibleToCurrentUser(99L)).thenReturn(false);

        when(videoRepository.findByIdIn(any(Collection.class)))
                .thenReturn(List.of(v10, vHidden, v12, v11));
        when(seriesRepository.findAllById(any(Collection.class)))
                .thenReturn(List.of(seriesA));
        when(favoriteService.statusMap(any(Long.class), any(String.class), any(Collection.class)))
                .thenReturn(Map.of());

        ApiResponse<PageResponse<SeriesListDTO>> body =
                controller.getActorWorks(5L, 0, 20, request).getBody();

        assertThat(body).isNotNull();
        PageResponse<SeriesListDTO> data = body.getData();
        assertThat(data.getTotalElements()).isEqualTo(2);
        // 同系列两集折叠为一部；按年份降序：2019 系列 > 2017 战狼
        assertThat(data.getContent()).extracting(SeriesListDTO::getType)
                .containsExactly("series", "standalone");
        assertThat(data.getContent()).extracting(SeriesListDTO::getTitle)
                .containsExactly("流浪地球系列", "战狼");

        ArgumentCaptor<Set> videoIdsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(videoRepository).findByIdIn(videoIdsCaptor.capture());
        // 聚合阶段含 v13，库过滤在取回后执行
        assertThat(videoIdsCaptor.getValue()).containsExactlyInAnyOrder(10L, 11L, 12L, 13L);

        ArgumentCaptor<Collection> seriesIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(seriesRepository).findAllById(seriesIdsCaptor.capture());
        assertThat(seriesIdsCaptor.getValue()).containsExactly(900L);
    }

    @Test
    void emptyResults_returnsEmptyPage() {
        VideoActor actor = new VideoActor();
        actor.setId(5L);
        actor.setName("不存在的人");
        when(actorRepository.findById(5L)).thenReturn(Optional.of(actor));
        when(actorRepository.findByNameIgnoreCase("不存在的人")).thenReturn(List.of());

        var resp = controller.getActorWorks(5L, 0, 20, request);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData().getTotalElements()).isZero();
    }
}