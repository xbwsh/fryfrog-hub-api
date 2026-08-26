package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private SeriesService seriesService;
    @Mock
    private VideoControllerSupport support;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private VideoController controller;

    private Video video(long id, String title, long libraryId) {
        Video v = new Video();
        v.setId(id);
        v.setTitle(title);
        v.setLibraryId(libraryId);
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
    void aggregatesBySourceIdAndName_deduplicatesAndFiltersLibraries() {
        VideoActor actor = new VideoActor();
        actor.setId(5L);
        actor.setName("吴京");
        actor.setSourceActorId(1001L);
        when(actorRepository.findById(5L)).thenReturn(Optional.of(actor));

        Video v10 = video(10L, "流浪地球", 1L);
        v10.setYear(2019);
        Video v11 = video(11L, "战狼", 1L);
        v11.setYear(2015);
        Video v12 = video(12L, "战狼2", 2L);
        v12.setYear(2017);
        Video vHidden = video(13L, "隐藏库影片", 99L);
        vHidden.setYear(2016);

        when(actorRepository.findBySourceActorId(1001L))
                .thenReturn(List.of(actorLink(v10), actorLink(v11)));
        when(actorRepository.findByNameIgnoreCase("吴京"))
                .thenReturn(List.of(actorLink(v10), actorLink(v12), actorLink(vHidden)));
        when(support.isLibraryVisibleToCurrentUser(1L)).thenReturn(true);
        when(support.isLibraryVisibleToCurrentUser(2L)).thenReturn(true);
        when(support.isLibraryVisibleToCurrentUser(99L)).thenReturn(false);

when(videoRepository.findByIdIn(any(Collection.class)))
                .thenReturn(List.of(v10, vHidden, v12, v11));
        when(support.toPageDTO(any(), anyInt(), anyInt(), anyLong(), anyLong()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 3));

        ApiResponse<PageResponse<VideoDTO>> body =
                controller.getActorWorks(5L, 0, 20, request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData().getTotalElements()).isEqualTo(3);

        // 聚合阶段含 v13，库过滤在取回后执行（见下方 slice 断言）
        ArgumentCaptor<Set> videoIdsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(videoRepository).findByIdIn(videoIdsCaptor.capture());
        assertThat(videoIdsCaptor.getValue()).containsExactlyInAnyOrder(10L, 11L, 12L, 13L);

        ArgumentCaptor<List> sliceCaptor = ArgumentCaptor.forClass(List.class);
        verify(support).toPageDTO(sliceCaptor.capture(), anyInt(), anyInt(), anyLong(), anyLong());
        // v13(库99不可见) 被过滤；年份降序 → 2019(v10) > 2017(v12) > 2015(v11)
        assertThat(sliceCaptor.getValue()).extracting("id").containsExactly(10L, 12L, 11L);
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