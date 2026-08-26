package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.dto.ActorDetailDTO;
import com.fryfrog.hub.video.dto.TmdbPersonDetail;
import com.fryfrog.hub.video.model.ActorProfile;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.repository.ActorProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class ActorProfileServiceTest {

    @Mock
    private ActorProfileRepository repository;
    @Mock
    private TmdbService tmdbService;

    private ActorProfileService service;

    @BeforeEach
    void setUp() {
        service = new ActorProfileService(repository, tmdbService);
        ReflectionTestUtils.setField(service, "ttlHours", 168L);
    }

    private VideoActor actor(Long id, Long tmdbId, String name) {
        VideoActor a = new VideoActor();
        a.setId(id);
        a.setName(name);
        a.setSourceActorId(tmdbId);
        return a;
    }

    private TmdbPersonDetail person(Long id, String name, String bio) {
        TmdbPersonDetail p = new TmdbPersonDetail();
        p.setId(id);
        p.setName(name);
        p.setBiography(bio);
        p.setAlsoKnownAs(List.of("别名A"));
        p.setBirthday("1996-06-01");
        p.setGender(2);
        p.setPlaceOfBirth("London");
        TmdbPersonDetail.Credits credits = new TmdbPersonDetail.Credits();
        credits.setCast(List.of(credit(100L, "movie", "A", 9000)));
        credits.setCrew(List.of());
        p.setCombinedCredits(credits);
        return p;
    }

    private TmdbPersonDetail.Credit credit(Long id, String mediaType, String title, int votes) {
        TmdbPersonDetail.Credit c = new TmdbPersonDetail.Credit();
        c.setId(id);
        c.setMediaType(mediaType);
        c.setTitle(title);
        c.setVoteCount(votes);
        c.setReleaseDate("2017-06-28");
        c.setPosterPath("/p.jpg");
        return c;
    }

    @Test
    void freshCache_hitsDatabase_noTmdbCall() {
        VideoActor actor = actor(5L, 1136406L, "Tom Holland");
        ActorProfile profile = new ActorProfile();
        profile.setActorId(5L);
        profile.setName("Tom Holland");
        profile.setTmdbId(1136406L);
        profile.setBiography("cached bio");
        profile.setFetchedAt(LocalDateTime.now());
        profile.setCastJson("[{\"id\":100,\"title\":\"A\",\"voteCount\":9000}]");
        profile.setCrewJson("[]");
        when(repository.findByActorId(5L)).thenReturn(Optional.of(profile));

        ActorDetailDTO dto = service.getActorDetail(actor);

        assertThat(dto.getBiography()).isEqualTo("cached bio");
        assertThat(dto.getCastCount()).isEqualTo(1);
        assertThat(dto.getTotalCredits()).isEqualTo(1);
        verify(tmdbService, never()).getPersonDetail(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void expiredCache_refreshesFromTmdb_andPersists() {
        VideoActor actor = actor(5L, 1136406L, "Tom Holland");
        ActorProfile stale = new ActorProfile();
        stale.setActorId(5L);
        stale.setFetchedAt(LocalDateTime.now().minusDays(10));
        when(repository.findByActorId(5L)).thenReturn(Optional.of(stale));
        when(tmdbService.getPersonDetail(1136406L)).thenReturn(person(1136406L, "Tom Holland", "fresh bio"));
        when(tmdbService.buildImageUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> "https://image.tmdb.org/t/p/w500" + inv.getArgument(0));

        ActorDetailDTO dto = service.getActorDetail(actor);

        assertThat(dto.getBiography()).isEqualTo("fresh bio");
        assertThat(dto.getAlsoKnownAs()).containsExactly("别名A");
        assertThat(dto.getGenderLabel()).isEqualTo("男");
        assertThat(dto.getCastCount()).isEqualTo(1);
        verify(repository).save(org.mockito.ArgumentMatchers.any(ActorProfile.class));
    }

    @Test
    void noCache_tmdbUnavailable_returnsLocalOnly() {
        VideoActor actor = actor(5L, 1136406L, "Tom Holland");
        when(repository.findByActorId(5L)).thenReturn(Optional.empty());
        when(tmdbService.getPersonDetail(1136406L)).thenReturn(null);

        ActorDetailDTO dto = service.getActorDetail(actor);

        assertThat(dto.getName()).isEqualTo("Tom Holland");
        assertThat(dto.getTotalCredits()).isZero();
        assertThat(dto.getCredits().getCast()).isEmpty();
    }

    @Test
    void expiredCache_tmdbUnavailable_returnsStaleCache() {
        VideoActor actor = actor(5L, 1136406L, "Tom Holland");
        ActorProfile stale = new ActorProfile();
        stale.setActorId(5L);
        stale.setName("Tom Holland");
        stale.setBiography("stale bio");
        stale.setFetchedAt(LocalDateTime.now().minusDays(10));
        stale.setCastJson("[]");
        stale.setCrewJson("[]");
        when(repository.findByActorId(5L)).thenReturn(Optional.of(stale));
        when(tmdbService.getPersonDetail(1136406L)).thenReturn(null);

        ActorDetailDTO dto = service.getActorDetail(actor);

        assertThat(dto.getBiography()).isEqualTo("stale bio");
    }

    @Test
    void localOnly_whenNoTmdbId() {
        VideoActor actor = actor(5L, null, "本地演员");
        when(repository.findByActorId(5L)).thenReturn(Optional.empty());

        ActorDetailDTO dto = service.getActorDetail(actor);

        assertThat(dto.getTmdbId()).isNull();
        assertThat(dto.getTotalCredits()).isZero();
        verify(tmdbService, never()).getPersonDetail(org.mockito.ArgumentMatchers.anyLong());
    }
}