package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.FavoriteRepository;
import com.fryfrog.hub.video.repository.WatchProgressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoCleanupServiceTest {

    @Mock
    private WatchProgressRepository watchProgressRepository;
    @Mock
    private FavoriteRepository favoriteRepository;

    @InjectMocks
    private VideoCleanupService service;

    @Test
    void deleteUserData_deletesProgressAndFavoritesForVideo() {
        service.deleteUserData(42L);

        verify(watchProgressRepository).deleteByVideo_Id(42L);
        verify(favoriteRepository).deleteByContentTypeAndContentId(Favorite.TYPE_VIDEO, 42L);
    }

    @Test
    void deleteUserData_batchSkipsEmpty() {
        service.deleteUserData(List.of());

        verify(watchProgressRepository, org.mockito.Mockito.never()).deleteByVideo_IdIn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteUserData_batchDeletesInBulk() {
        service.deleteUserData(List.of(1L, 2L, 3L));

        verify(watchProgressRepository).deleteByVideo_IdIn(List.of(1L, 2L, 3L));
        verify(favoriteRepository).deleteByContentTypeAndContentIdIn(Favorite.TYPE_VIDEO, List.of(1L, 2L, 3L));
    }

    @Test
    void deleteFrameCacheDir_removesCacheDirectory(@TempDir Path tempDir) throws Exception {
        Path videoDir = Files.createDirectories(tempDir.resolve("video"));
        Path cacheDir = Files.createDirectories(videoDir.resolve(".frames-7"));
        Files.writeString(cacheDir.resolve("frame-0.jpg"), "data");

        Video video = new Video();
        video.setId(7L);
        video.setFilePath(videoDir.resolve("movie.mkv").toString());

        service.deleteFrameCacheDir(video);

        assertThat(cacheDir).doesNotExist();
    }

    @Test
    void deleteFrameCacheDir_missingDirIsNoop(@TempDir Path tempDir) {
        Video video = new Video();
        video.setId(99L);
        video.setFilePath(tempDir.resolve("nope.mkv").toString());

        service.deleteFrameCacheDir(video);

        assertThat(tempDir.resolve(".frames-99")).doesNotExist();
    }
}
