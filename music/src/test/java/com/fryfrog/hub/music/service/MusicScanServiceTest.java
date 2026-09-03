package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.music.model.MusicAlbum;
import com.fryfrog.hub.music.model.MusicArtist;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicAlbumRepository;
import com.fryfrog.hub.music.repository.MusicArtistRepository;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import com.fryfrog.hub.music.service.MusicScanService;
import com.fryfrog.hub.music.service.MusicTagReaderService;
import com.fryfrog.hub.mediacore.service.MediaProbeService;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class MusicScanServiceTest {

    @Mock
    private MusicArtistRepository artistRepository;
    @Mock
    private MusicAlbumRepository albumRepository;
    @Mock
    private MusicSongRepository songRepository;
    @Mock
    private MusicCleanupService cleanupService;
    @Mock
    private MediaProbeService probeService;
    @Mock
    private ScrapeProgressService progressService;
    @Mock
    private MediaLibraryService mediaLibraryService;
    @Mock
    private MusicTagReaderService tagReaderService;

    @InjectMocks
    private MusicScanService scanService;

    @TempDir
    Path tempDir;

    @Test
    void saveSongParsesTagsAndCreatesArtistAlbumSong() throws Exception {
        Path albumDir = Files.createDirectories(tempDir.resolve("周杰伦").resolve("七里香"));
        Files.createFile(albumDir.resolve("cover.jpg"));
        Path songFile = albumDir.resolve("01 七里香.mp3");

        when(songRepository.findByFilePath(eq(songFile.toAbsolutePath().normalize().toString())))
                .thenReturn(Optional.empty());
        when(probeService.probeAudioInfo(any())).thenReturn(Map.of(
                "duration", 247.0,
                "bitrate", 320000L,
                "format", "mp3",
                "tags", Map.of(
                        "title", "七里香",
                        "artist", "周杰伦",
                        "album", "七里香",
                        "track", "1",
                        "date", "2004",
                        "genre", "Pop")
        ));
        when(artistRepository.findFirstByNameAndLibraryId("周杰伦", 1L)).thenReturn(Optional.empty());
        when(artistRepository.save(any())).thenAnswer(inv -> {
            MusicArtist a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(albumRepository.findFirstByTitleAndArtistNameAndLibraryId("七里香", "周杰伦", 1L))
                .thenReturn(Optional.empty());
        when(albumRepository.save(any())).thenAnswer(inv -> {
            MusicAlbum a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(songRepository.save(any())).thenAnswer(inv -> {
            MusicSong s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        MusicSong song = scanService.saveSong(songFile, 1L);

        assertThat(song.getTitle()).isEqualTo("七里香");
        assertThat(song.getArtistName()).isEqualTo("周杰伦");
        assertThat(song.getAlbumName()).isEqualTo("七里香");
        assertThat(song.getTrackNumber()).isEqualTo(1);
        assertThat(song.getYear()).isEqualTo(2004);
        assertThat(song.getDurationSeconds()).isEqualTo(247.0);
        assertThat(song.getLibraryId()).isEqualTo(1L);
        assertThat(song.getAlbum().getCoverArtPath()).isEqualTo(albumDir.resolve("cover.jpg").toString());
        assertThat(song.getArtist().getCoverArtPath()).isNull();
        verify(artistRepository).save(any());
        verify(albumRepository).save(any());
        verify(songRepository).save(any());
    }

    @Test
    void saveSongFallsBackToDirectoryNames() throws Exception {
        Path songDir = Files.createDirectories(tempDir.resolve("郭顶").resolve("飞行器的执行周期"));
        Path songFile = songDir.resolve("水星记.flac");

        when(songRepository.findByFilePath(any())).thenReturn(Optional.empty());
        when(probeService.probeAudioInfo(any())).thenReturn(Map.of(
                "duration", 200.0,
                "tags", Map.of() // 无标签，退化为目录名
        ));
        when(artistRepository.findFirstByNameAndLibraryId("郭顶", 2L)).thenReturn(Optional.empty());
        when(artistRepository.save(any())).thenAnswer(inv -> {
            MusicArtist a = inv.getArgument(0);
            a.setId(2L);
            return a;
        });
        when(albumRepository.findFirstByTitleAndArtistNameAndLibraryId("飞行器的执行周期", "郭顶", 2L))
                .thenReturn(Optional.empty());
        when(albumRepository.save(any())).thenAnswer(inv -> {
            MusicAlbum a = inv.getArgument(0);
            a.setId(2L);
            return a;
        });
        when(songRepository.save(any())).thenAnswer(inv -> {
            MusicSong s = inv.getArgument(0);
            s.setId(2L);
            return s;
        });

        MusicSong song = scanService.saveSong(songFile, 2L);

        assertThat(song.getTitle()).isEqualTo("水星记");
        assertThat(song.getArtistName()).isEqualTo("郭顶");
        assertThat(song.getAlbumName()).isEqualTo("飞行器的执行周期");
    }

    @Test
    void saveSongHandlesEmptyTrackTags() throws Exception {
        // 回归：ffprobe 对无曲目号文件输出 track/disc 为 " / "，旧实现 split("/")[0] 数组越界
        Path songDir = Files.createDirectories(tempDir.resolve("周杰伦").resolve("Jay"));
        Path songFile = songDir.resolve("反方向的钟.wav");

        when(songRepository.findByFilePath(any())).thenReturn(Optional.empty());
        when(probeService.probeAudioInfo(any())).thenReturn(Map.of(
                "duration", 257.6,
                "tags", Map.of(
                        "title", "反方向的钟",
                        "artist", "周杰伦",
                        "album", "Jay",
                        "track", " / ",
                        "disc", " / ",
                        "date", " ")
        ));
        when(artistRepository.findFirstByNameAndLibraryId("周杰伦", 3L)).thenReturn(Optional.empty());
        when(artistRepository.save(any())).thenAnswer(inv -> {
            MusicArtist a = inv.getArgument(0);
            a.setId(3L);
            return a;
        });
        when(albumRepository.findFirstByTitleAndArtistNameAndLibraryId("Jay", "周杰伦", 3L))
                .thenReturn(Optional.empty());
        when(albumRepository.save(any())).thenAnswer(inv -> {
            MusicAlbum a = inv.getArgument(0);
            a.setId(3L);
            return a;
        });
        when(songRepository.save(any())).thenAnswer(inv -> {
            MusicSong s = inv.getArgument(0);
            s.setId(3L);
            return s;
        });

        MusicSong song = scanService.saveSong(songFile, 3L);

        assertThat(song.getTitle()).isEqualTo("反方向的钟");
        assertThat(song.getTrackNumber()).isNull();
        assertThat(song.getDiscNumber()).isNull();
        assertThat(song.getYear()).isNull();
        assertThat(song.getDurationSeconds()).isEqualTo(257.6);
    }
}