package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class VideoOrganizeServiceTest {

    @Mock private VideoRepository repository;
    @Mock private NfoService nfoService;

    @InjectMocks
    private VideoOrganizeService service;

    @Test
    void moveToUnscrapedDir_movesVideoWithSubtitleAndUpdatesPath(@TempDir Path tempDir) throws IOException {
        Path libRoot = tempDir.resolve("library");
        Files.createDirectories(libRoot);
        Path videoFile = libRoot.resolve("test movie.mkv");
        Files.writeString(videoFile, "video");
        Path subtitle = libRoot.resolve("test movie.ass");
        Files.writeString(subtitle, "sub");

        Video video = new Video();
        video.setId(1L);
        video.setFileName("test movie.mkv");
        video.setFilePath(videoFile.toString());
        video.setLibraryId(10L);

        Path unscrapedDir = libRoot.resolve(NfoService.UNSCRAPED_DIR_NAME);
        when(nfoService.isInUnscrapedDir(video)).thenReturn(false);
        when(nfoService.getUnscrapedDir(video)).thenReturn(unscrapedDir);
        when(nfoService.getBaseName("test movie.mkv")).thenReturn("test movie");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());
        when(repository.save(video)).thenReturn(video);

        boolean moved = service.moveToUnscrapedDir(video);

        assertThat(moved).isTrue();
        Path target = unscrapedDir.resolve("test movie.mkv");
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.exists(unscrapedDir.resolve("test movie.ass"))).isTrue();
        assertThat(video.getFilePath()).isEqualTo(target.toString());
        verify(repository).save(video);
    }

    @Test
    void moveToUnscrapedDir_keepsLibraryRootWhenSourceDirBecomesEmpty(@TempDir Path tempDir) throws IOException {
        Path libRoot = tempDir.resolve("library");
        Files.createDirectories(libRoot);
        Path videoFile = libRoot.resolve("solo.mkv");
        Files.writeString(videoFile, "video");

        Video video = new Video();
        video.setId(1L);
        video.setFileName("solo.mkv");
        video.setFilePath(videoFile.toString());
        video.setLibraryId(10L);

        Path unscrapedDir = libRoot.resolve(NfoService.UNSCRAPED_DIR_NAME);
        when(nfoService.isInUnscrapedDir(video)).thenReturn(false);
        when(nfoService.getUnscrapedDir(video)).thenReturn(unscrapedDir);
        when(nfoService.getBaseName("solo.mkv")).thenReturn("solo");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());
        when(repository.save(video)).thenReturn(video);

        service.moveToUnscrapedDir(video);

        // 源视频所在目录被移空后，库根目录不能被删除
        assertThat(Files.exists(libRoot)).isTrue();
        assertThat(Files.exists(unscrapedDir.resolve("solo.mkv"))).isTrue();
    }

    @Test
    void moveToUnscrapedDir_skipsWhenAlreadyInUnscrapedDir() {
        Video video = new Video();
        video.setId(1L);
        video.setFileName("x.mkv");
        video.setFilePath("D:/library/" + NfoService.UNSCRAPED_DIR_NAME + "/x.mkv");
        video.setLibraryId(10L);

        when(nfoService.isInUnscrapedDir(video)).thenReturn(true);

        boolean moved = service.moveToUnscrapedDir(video);

        assertThat(moved).isFalse();
        verify(repository, never()).save(any());
    }
}
