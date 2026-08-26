package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.video.dto.ActorDetailDTO;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import com.fryfrog.hub.video.service.ActorProfileService;
import com.fryfrog.hub.video.service.FavoriteService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoControllerActorDetailTest {

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

    @InjectMocks
    private VideoController controller;

    @Test
    void actorNotFound_throws404() {
        when(actorRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getActorDetail(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void passesThroughServiceResult_andBackfillsImageUrl() {
        Video video = new Video();
        video.setId(10L);
        video.setLibraryId(1L);
        VideoActor actor = new VideoActor();
        actor.setId(5L);
        actor.setName("Tom Holland");
        actor.setSourceActorId(1136406L);
        actor.setVideo(video);
        when(actorRepository.findById(5L)).thenReturn(Optional.of(actor));
        when(videoRepository.findById(10L)).thenReturn(Optional.of(video));

        ActorDetailDTO fromService = new ActorDetailDTO();
        fromService.setId(5L);
        fromService.setName("Tom Holland");
        fromService.setTmdbId(1136406L);
        fromService.setBiography("bio");
        fromService.setImageUrl("/api/v1/video/actor/5/image?exp=1&sig=x");
        // imageUrl 由 service 返回（非 null 时直接透传，不覆盖）
        when(actorProfileService.getActorDetail(actor)).thenReturn(fromService);

        ApiResponse<ActorDetailDTO> body = controller.getActorDetail(5L).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData()).isSameAs(fromService);
        assertThat(body.getData().getImageUrl()).isEqualTo("/api/v1/video/actor/5/image?exp=1&sig=x");
    }

    @Test
    void backfillsImageUrlWhenServiceOmitsIt() {
        VideoActor actor = new VideoActor();
        actor.setId(5L);
        actor.setName("Tom Holland");
        actor.setSourceActorId(1136406L);
        when(actorRepository.findById(5L)).thenReturn(Optional.of(actor));

        ActorDetailDTO fromService = new ActorDetailDTO();
        fromService.setId(5L);
        when(actorProfileService.getActorDetail(actor)).thenReturn(fromService);

        ApiResponse<ActorDetailDTO> body = controller.getActorDetail(5L).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData().getImageUrl()).isNotBlank();
    }
}