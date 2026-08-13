package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.model.UserLibrary;
import com.fryfrog.hub.common.repository.MediaLibraryRepository;
import com.fryfrog.hub.common.repository.UserLibraryRepository;
import com.fryfrog.hub.common.security.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class MediaLibraryServiceTest {

    @Mock
    private MediaLibraryRepository repository;

    @Mock
    private UserLibraryRepository userLibraryRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private MediaLibraryService service;

    private MediaLibrary lib(long id) {
        MediaLibrary lib = new MediaLibrary();
        lib.setId(id);
        lib.setEnabled(true);
        return lib;
    }

    private void stubEnabled(long... ids) {
        when(repository.findByEnabledTrueOrderBySortOrderAsc())
                .thenReturn(java.util.Arrays.stream(ids).mapToObj(this::lib).toList());
    }

    @Test
    void adminSeesAllEnabledLibraries() {
        stubEnabled(1L, 2L);
        when(userService.isAdmin(9L)).thenReturn(true);

        assertThat(service.getAllowedLibraryIds(9L)).containsExactly(1L, 2L);
    }

    @Test
    void anonymousSeesAllEnabledLibraries() {
        stubEnabled(1L, 2L);

        assertThat(service.getAllowedLibraryIds(UserContext.ANONYMOUS_ID)).containsExactly(1L, 2L);
    }

    @Test
    void nullUserIdSeesAllEnabledLibraries() {
        stubEnabled(1L);

        assertThat(service.getAllowedLibraryIds(null)).containsExactly(1L);
    }

    @Test
    void normalUserSeesOnlyAssignedAndEnabled() {
        stubEnabled(1L, 2L, 3L);
        when(userService.isAdmin(7L)).thenReturn(false);
        when(userLibraryRepository.findByUserId(7L))
                .thenReturn(List.of(UserLibrary.builder().userId(7L).libraryId(2L).build()));

        assertThat(service.getAllowedLibraryIds(7L)).containsExactly(2L);
    }

    @Test
    void normalUserWithoutAssignmentSeesNothing() {
        stubEnabled(1L, 2L);
        when(userService.isAdmin(7L)).thenReturn(false);
        when(userLibraryRepository.findByUserId(7L)).thenReturn(List.of());

        assertThat(service.getAllowedLibraryIds(7L)).isEmpty();
    }

    @Test
    void getAllowableLibraryIds_withoutRequestUsesEnabled() {
        stubEnabled(1L, 2L);

        assertThat(service.getAllowableLibraryIds()).containsExactly(1L, 2L);
    }

    @Test
    void assignLibraries_createsNewAndRemovesUnassigned() {
        when(userService.getUser(7L)).thenReturn(new User());
        when(userLibraryRepository.findByUserId(7L))
                .thenReturn(List.of(UserLibrary.builder().userId(7L).libraryId(1L).build()));
        when(repository.findById(2L)).thenReturn(java.util.Optional.of(lib(2L)));

        service.assignLibraries(7L, List.of(2L));

        verify(userLibraryRepository).save(org.mockito.ArgumentMatchers.argThat(
                f -> f.getUserId() == 7L && f.getLibraryId() == 2L));
        verify(userLibraryRepository).deleteByUserIdAndLibraryId(7L, 1L);
    }

    @Test
    void assignLibraries_emptyClearsAll() {
        when(userService.getUser(7L)).thenReturn(new User());
        when(userLibraryRepository.findByUserId(7L))
                .thenReturn(List.of(UserLibrary.builder().userId(7L).libraryId(1L).build()));

        service.assignLibraries(7L, List.of());

        verify(userLibraryRepository).deleteByUserIdAndLibraryId(7L, 1L);
        verify(userLibraryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void assignLibraries_ignoresDuplicateEntries() {
        when(userService.getUser(7L)).thenReturn(new User());
        when(userLibraryRepository.findByUserId(7L)).thenReturn(List.of());
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(lib(1L)));

        service.assignLibraries(7L, List.of(1L, 1L));

        verify(userLibraryRepository).save(org.mockito.ArgumentMatchers.argThat(
                f -> f.getUserId() == 7L && f.getLibraryId() == 1L));
    }
}