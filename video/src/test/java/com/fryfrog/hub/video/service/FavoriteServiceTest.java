package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.repository.FavoriteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    @Mock
    private FavoriteRepository repository;

    @InjectMocks
    private FavoriteService service;

    @Test
    void setFavorite_createWhenAbsent() {
        when(repository.findByUserIdAndContentTypeAndContentId(1L, "VIDEO", 100L)).thenReturn(Optional.empty());

        service.setFavorite(1L, "VIDEO", 100L, true);

        verify(repository).save(any(Favorite.class));
    }

    @Test
    void setFavorite_skipWhenAlreadyExists() {
        when(repository.findByUserIdAndContentTypeAndContentId(1L, "VIDEO", 100L))
                .thenReturn(Optional.of(new Favorite()));

        service.setFavorite(1L, "VIDEO", 100L, true);

        verify(repository, never()).save(any(Favorite.class));
    }

    @Test
    void setFavorite_deleteWhenFalse() {
        service.setFavorite(1L, "VIDEO", 100L, false);

        verify(repository).deleteByUserIdAndContentTypeAndContentId(1L, "VIDEO", 100L);
    }

    @Test
    void statusMap_marksOnlyFavoritedIds() {
        Favorite fav = new Favorite(1L, "VIDEO", 100L);
        when(repository.findByUserIdAndContentTypeAndContentIdIn(1L, "VIDEO", List.of(100L, 200L)))
                .thenReturn(List.of(fav));

        Map<Long, Boolean> result = service.statusMap(1L, "VIDEO", List.of(100L, 200L));

        assertThat(result).isEqualTo(Map.of(100L, Boolean.TRUE));
    }

    @Test
    void statusMap_emptyIdsReturnsEmpty() {
        assertThat(service.statusMap(1L, "VIDEO", List.of())).isEmpty();
    }

    @Test
    void contentIds_returnsIds() {
        when(repository.findByUserIdAndContentType(9L, "SERIES"))
                .thenReturn(List.of(new Favorite(9L, "SERIES", 55L),
                        new Favorite(9L, "SERIES", 66L)));

        assertThat(service.contentIds(9L, "SERIES")).containsExactly(55L, 66L);
    }
}